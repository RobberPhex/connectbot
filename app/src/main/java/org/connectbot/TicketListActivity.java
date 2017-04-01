/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2017 Kenny Root, Jeffrey Sharkey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.connectbot;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Collections;
import java.util.EventListener;
import java.util.LinkedList;
import java.util.List;

import org.connectbot.bean.PubkeyBean;
import org.connectbot.service.TerminalManager;
import org.connectbot.util.PubkeyDatabase;
import org.connectbot.util.PubkeyUtils;
import org.openintents.intents.FileManagerIntents;

import com.trilead.ssh2.crypto.Base64;
import com.trilead.ssh2.crypto.PEMDecoder;
import com.trilead.ssh2.crypto.PEMStructure;

import android.annotation.TargetApi;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.support.annotation.VisibleForTesting;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.ClipboardManager;
import android.util.Log;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MenuItem.OnMenuItemClickListener;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * List public keys in database by nickname and describe their properties. Allow users to import,
 * generate, rename, and delete key pairs.
 *
 * @author Kenny Root
 */
public class TicketListActivity extends AppCompatListActivity implements EventListener {
	public final static String TAG = "CB.TicketListActivity";

	private TerminalManager bound = null;

	private ServiceConnection connection = new ServiceConnection() {
		public void onServiceConnected(ComponentName className, IBinder service) {
			bound = ((TerminalManager.TerminalBinder) service).getService();

			// update our listview binder to find the service
			updateList();
		}

		public void onServiceDisconnected(ComponentName className) {
			bound = null;
			updateList();
		}
	};

	@Override
	public void onStart() {
		super.onStart();

		bindService(new Intent(this, TerminalManager.class), connection, Context.BIND_AUTO_CREATE);

		updateList();
	}

	@Override
	public void onStop() {
		super.onStop();

		unbindService(connection);
	}

	@Override
	public void onCreate(Bundle icicle) {
		super.onCreate(icicle);
		setContentView(R.layout.act_pubkeylist);

		mListView = (RecyclerView) findViewById(R.id.list);
		mListView.setHasFixedSize(true);
		mListView.setLayoutManager(new LinearLayoutManager(this));
		mListView.addItemDecoration(new ListItemDecoration(this));

		mEmptyView = findViewById(R.id.empty);

		registerForContextMenu(mListView);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.ticket_list_activity_menu, menu);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.add_new_ticket_icon:
			startActivity(new Intent(this, GeneratePubkeyActivity.class));
			return true;
		default:
			return super.onOptionsItemSelected(item);
		}
	}

	protected void updateList() {
		PubkeyDatabase pubkeyDb = PubkeyDatabase.get(TicketListActivity.this);

		mAdapter = new PubkeyAdapter(this, pubkeyDb.allPubkeys());
		mListView.setAdapter(mAdapter);
		adjustViewVisibility();
	}

	public class PubkeyViewHolder extends ItemViewHolder {
		public final ImageView icon;
		public final TextView nickname;
		public final TextView caption;

		public PubkeyBean pubkey;

		public PubkeyViewHolder(View v) {
			super(v);

			icon = (ImageView) v.findViewById(android.R.id.icon);
			nickname = (TextView) v.findViewById(android.R.id.text1);
			caption = (TextView) v.findViewById(android.R.id.text2);
		}

		@Override
		public void onClick(View v) {
			boolean loaded = bound != null && bound.isKeyLoaded(pubkey.getNickname());

			// handle toggling key in-memory on/off
			if (loaded) {
				bound.removeKey(pubkey.getNickname());
				updateList();
			}
		}

		@Override
		public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
			// Create menu to handle deleting and editing pubkey
			menu.setHeaderTitle(pubkey.getNickname());

			// TODO: option load/unload key from in-memory list
			// prompt for password as needed for passworded keys

			// cant change password or clipboard imported keys
			final boolean imported = PubkeyDatabase.KEY_TYPE_IMPORTED.equals(pubkey.getType());
			final boolean loaded = bound != null && bound.isKeyLoaded(pubkey.getNickname());

			MenuItem load = menu.add(loaded ? R.string.pubkey_memory_unload : R.string.pubkey_memory_load);
			load.setOnMenuItemClickListener(new OnMenuItemClickListener() {
				public boolean onMenuItemClick(MenuItem item) {
					if (loaded) {
						bound.removeKey(pubkey.getNickname());
						updateList();
					}
					return true;
				}
			});

			MenuItem onstartToggle = menu.add(R.string.pubkey_load_on_start);
			onstartToggle.setEnabled(!pubkey.isEncrypted());
			onstartToggle.setCheckable(true);
			onstartToggle.setChecked(pubkey.isStartup());
			onstartToggle.setOnMenuItemClickListener(new OnMenuItemClickListener() {
				public boolean onMenuItemClick(MenuItem item) {
					// toggle onstart status
					pubkey.setStartup(!pubkey.isStartup());
					PubkeyDatabase pubkeyDb = PubkeyDatabase.get(TicketListActivity.this);
					pubkeyDb.savePubkey(pubkey);
					updateList();
					return true;
				}
			});

			MenuItem changePassword = menu.add(R.string.pubkey_change_password);
			changePassword.setEnabled(!imported);
			changePassword.setOnMenuItemClickListener(new OnMenuItemClickListener() {
				public boolean onMenuItemClick(MenuItem item) {
					final View changePasswordView =
							View.inflate(TicketListActivity.this, R.layout.dia_changepassword, null);
					changePasswordView.findViewById(R.id.old_password_prompt)
							.setVisibility(pubkey.isEncrypted() ? View.VISIBLE : View.GONE);
					new android.support.v7.app.AlertDialog.Builder(
									TicketListActivity.this, R.style.AlertDialogTheme)
							.setView(changePasswordView)
							.setPositiveButton(R.string.button_change, new OnClickListener() {
								public void onClick(DialogInterface dialog, int which) {
									String oldPassword = ((EditText) changePasswordView.findViewById(R.id.old_password)).getText().toString();
									String password1 = ((EditText) changePasswordView.findViewById(R.id.password1)).getText().toString();
									String password2 = ((EditText) changePasswordView.findViewById(R.id.password2)).getText().toString();

									if (!password1.equals(password2)) {
										new android.support.v7.app.AlertDialog.Builder(
														TicketListActivity.this,
														R.style.AlertDialogTheme)
												.setMessage(R.string.alert_passwords_do_not_match_msg)
												.setPositiveButton(android.R.string.ok, null)
												.create().show();
										return;
									}

									try {
										if (!pubkey.changePassword(oldPassword, password1))
											new android.support.v7.app.AlertDialog.Builder(
															TicketListActivity.this,
															R.style.AlertDialogTheme)
													.setMessage(R.string.alert_wrong_password_msg)
													.setPositiveButton(android.R.string.ok, null)
													.create().show();
										else {
											PubkeyDatabase pubkeyDb = PubkeyDatabase.get(TicketListActivity.this);
											pubkeyDb.savePubkey(pubkey);
											updateList();
										}
									} catch (Exception e) {
										Log.e(TAG, "Could not change private key password", e);
										new android.support.v7.app.AlertDialog.Builder(
														TicketListActivity.this,
														R.style.AlertDialogTheme)
												.setMessage(R.string.alert_key_corrupted_msg)
												.setPositiveButton(android.R.string.ok, null)
												.create().show();
									}
								}
							})
							.setNegativeButton(android.R.string.cancel, null).create().show();

					return true;
				}
			});

			MenuItem confirmUse = menu.add(R.string.pubkey_confirm_use);
			confirmUse.setCheckable(true);
			confirmUse.setChecked(pubkey.isConfirmUse());
			confirmUse.setOnMenuItemClickListener(new OnMenuItemClickListener() {
				public boolean onMenuItemClick(MenuItem item) {
					// toggle confirm use
					pubkey.setConfirmUse(!pubkey.isConfirmUse());
					PubkeyDatabase pubkeyDb = PubkeyDatabase.get(TicketListActivity.this);
					pubkeyDb.savePubkey(pubkey);
					updateList();
					return true;
				}
			});

			MenuItem delete = menu.add(R.string.pubkey_delete);
			delete.setOnMenuItemClickListener(new OnMenuItemClickListener() {
				public boolean onMenuItemClick(MenuItem item) {
					// prompt user to make sure they really want this
					new android.support.v7.app.AlertDialog.Builder(
									TicketListActivity.this, R.style.AlertDialogTheme)
							.setMessage(getString(R.string.delete_message, pubkey.getNickname()))
							.setPositiveButton(R.string.delete_pos, new OnClickListener() {
								public void onClick(DialogInterface dialog, int which) {

									// dont forget to remove from in-memory
									if (loaded) {
										bound.removeKey(pubkey.getNickname());
									}

									// delete from backend database and update gui
									PubkeyDatabase pubkeyDb = PubkeyDatabase.get(TicketListActivity.this);
									pubkeyDb.deletePubkey(pubkey);
									updateList();
								}
							})
							.setNegativeButton(R.string.delete_neg, null).create().show();

					return true;
				}
			});
		}
	}

	@VisibleForTesting
	private class PubkeyAdapter extends ItemAdapter {
		private final List<PubkeyBean> pubkeys;

		public PubkeyAdapter(Context context, List<PubkeyBean> pubkeys) {
			super(context);
			this.pubkeys = pubkeys;
		}

		@Override
		public PubkeyViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
			View v = LayoutInflater.from(parent.getContext())
					.inflate(R.layout.item_pubkey, parent, false);
			return new PubkeyViewHolder(v);
		}

		public void onBindViewHolder(ItemViewHolder holder, int position) {
			PubkeyViewHolder pubkeyHolder = (PubkeyViewHolder) holder;

			PubkeyBean pubkey = pubkeys.get(position);
			pubkeyHolder.pubkey = pubkey;
			if (pubkey == null) {
				// Well, something bad happened. We can't continue.
				Log.e("PubkeyAdapter", "Pubkey bean is null!");

				pubkeyHolder.nickname.setText("Error during lookup");
			} else {
				pubkeyHolder.nickname.setText(pubkey.getNickname());
			}

			boolean imported = PubkeyDatabase.KEY_TYPE_IMPORTED.equals(pubkey.getType());

			if (imported) {
				try {
					PEMStructure struct = PEMDecoder.parsePEM(new String(pubkey.getPrivateKey()).toCharArray());
					String type;
					if (struct.pemType == PEMDecoder.PEM_RSA_PRIVATE_KEY) {
						type = "RSA";
					} else if (struct.pemType == PEMDecoder.PEM_DSA_PRIVATE_KEY) {
						type = "DSA";
					} else if (struct.pemType == PEMDecoder.PEM_EC_PRIVATE_KEY) {
						type = "EC";
					} else if (struct.pemType == PEMDecoder.PEM_OPENSSH_PRIVATE_KEY) {
						type = "OpenSSH";
					} else {
						throw new RuntimeException("Unexpected key type: " + struct.pemType);
					}
					pubkeyHolder.caption.setText(String.format("%s unknown-bit", type));
				} catch (IOException e) {
					Log.e(TAG, "Error decoding IMPORTED public key at " + pubkey.getId(), e);
				}
			} else {
				try {
					pubkeyHolder.caption.setText(pubkey.getDescription(getApplicationContext()));
				} catch (Exception e) {
					Log.e(TAG, "Error decoding public key at " + pubkey.getId(), e);
					pubkeyHolder.caption.setText(R.string.pubkey_unknown_format);
				}
			}

			if (bound == null) {
				pubkeyHolder.icon.setVisibility(View.GONE);
			} else {
				pubkeyHolder.icon.setVisibility(View.VISIBLE);

				if (bound.isKeyLoaded(pubkey.getNickname()))
					pubkeyHolder.icon.setImageState(new int[] { android.R.attr.state_checked }, true);
				else
					pubkeyHolder.icon.setImageState(new int[] { }, true);
			}
		}

		@Override
		public int getItemCount() {
			return pubkeys.size();
		}

		@Override
		public long getItemId(int position) {
			return pubkeys.get(position).getId();
		}
	}
}
