/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2019 Kenny Root, Jeffrey Sharkey
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

import org.apache.kerby.kerberos.kerb.KrbException;
import org.apache.kerby.kerberos.kerb.client.KrbClient;
import org.apache.kerby.kerberos.kerb.client.KrbConfig;
import org.apache.kerby.kerberos.kerb.client.KrbConfigKey;
import org.apache.kerby.kerberos.kerb.type.ticket.SgtTicket;
import org.apache.kerby.kerberos.kerb.type.ticket.TgtTicket;

class Main{
	public static void main(String[] args) throws KrbException {
		KrbConfig krbConfig = new KrbConfig();
		krbConfig.setString(KrbConfigKey.KDC_HOST, "kdc1.58os.org");
		krbConfig.setString(KrbConfigKey.KDC_REALM,"58OS.ORG");
		KrbClient krbClient = new KrbClient(krbConfig);
		krbClient.init();
		TgtTicket tgtTicket = krbClient.requestTgt("luyanbo@58OS.ORG", "Lu.Anjuke.2019");
		SgtTicket sgtTicket = krbClient.requestSgt(tgtTicket, "host/tjtx135-1-212.58os.org@58OS.ORG");
		System.out.println(sgtTicket.getEncKdcRepPart().getEndTime());
	}
}