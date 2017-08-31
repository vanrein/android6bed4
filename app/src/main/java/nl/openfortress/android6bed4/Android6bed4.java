/*
** Copyright 2015, Mohamed Naufal
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package nl.openfortress.android6bed4;


import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;

public class Android6bed4 extends ActionBarActivity
{
	private static final int VPN_REQUEST_CODE = 0x0F;

	private boolean waitingForVPNStart;

	private TextView tv_tunserver_ip;
	private Button vpnButton;
	private TextView tv_tunclient_port_number;
	private CheckBox cb_overtake_default_route;

	private BroadcastReceiver vpnStateReceiver = new BroadcastReceiver()
	{
		@Override
		public void onReceive(Context context, Intent intent)
		{
			if (TunnelService.BROADCAST_VPN_STATE.equals(intent.getAction()))
			{
				if (intent.getBooleanExtra("running", false))
					waitingForVPNStart = false;
			}
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		TunnelService.prefs = getPreferences (MODE_PRIVATE);
		setContentView(R.layout.user_interface);

		tv_tunserver_ip = (TextView) findViewById (R.id.tunserver_ip_string);
		vpnButton = (Button) findViewById(R.id.vpn);
		tv_tunclient_port_number = (TextView) findViewById (R.id.tunclient_port_number);
		cb_overtake_default_route = (CheckBox) findViewById (R.id.overtake_default_route);

		vpnButton.setOnClickListener(new View.OnClickListener()
		{
			@Override
			public void onClick(View v)
			{
				startVPN();
			}
		});

		String srv_ip = TunnelService.prefs.getString ("tunserver_ip", "");
		if (srv_ip.equals ("145.136.0.1")) {
			srv_ip = "";
		}
		tv_tunserver_ip.setText (srv_ip);

		int port = TunnelService.prefs.getInt ("tunclient_port", 0);
		if ((port > 0) && (port <= 65535) && ((port & 0x0001) == 0x0000)) {
			tv_tunclient_port_number.setText (Integer.toString (port));
		} else {
			tv_tunclient_port_number.setText ("");
		}

		cb_overtake_default_route.setChecked (TunnelService.prefs.getBoolean ("overtake_default_route", true));

		waitingForVPNStart = false;
		LocalBroadcastManager.getInstance(this).registerReceiver(vpnStateReceiver,
				new IntentFilter(TunnelService.BROADCAST_VPN_STATE));
	}

	private void saveSettings()
	{
		SharedPreferences.Editor ed = TunnelService.prefs.edit ();
		//
		// Check that the tunnel server IP address parses
		String pubsrv_ip;
		try {
			pubsrv_ip = tv_tunserver_ip.getText ().toString ().trim ();
			if (pubsrv_ip.length () > 0) {
				InetAddress test = Inet4Address.getByName (pubsrv_ip);
			} else {
				pubsrv_ip = "145.136.0.1";
			}
		} catch (Throwable thr) {
			pubsrv_ip = "145.136.0.1";
		}
		ed.putString ("tunserver_ip", pubsrv_ip);
		//
		// Check that the local port number is valid
		int new_port;
		try {
			String portstr = tv_tunclient_port_number.getText ().toString ().trim ();
			new_port = Integer.valueOf (portstr);
		} catch (Throwable thr) {
			new_port = 0;
		}
		if ((new_port <= 0) || (new_port > 65535) || ((new_port & 0x0001) != 0x0000)) {
			new_port = 0;
		}
		ed.putInt ("tunclient_port", new_port);

		ed.putBoolean ("overtake_default_route", cb_overtake_default_route.isChecked());

		ed.commit ();
	}

	private void startVPN()
	{
		saveSettings();
		Intent vpnIntent = VpnService.prepare(this);
		if (vpnIntent != null)
			startActivityForResult(vpnIntent, VPN_REQUEST_CODE);
		else
			onActivityResult(VPN_REQUEST_CODE, RESULT_OK, null);
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data)
	{
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK)
		{
			waitingForVPNStart = true;
			startService(new Intent(this, TunnelService.class));
			enableButton(false);
		}
	}

	@Override
	protected void onResume() {
		super.onResume();

		enableButton(!waitingForVPNStart && !TunnelService.isRunning());
	}

	private void enableButton(boolean enable)
	{
		if (enable)
		{
			vpnButton.setEnabled(true);
			tv_tunserver_ip.setEnabled(true);
			tv_tunclient_port_number.setEnabled(true);
			cb_overtake_default_route.setEnabled(true);
			vpnButton.setText(R.string.start_vpn);
		}
		else
		{
			vpnButton.setEnabled(false);
			tv_tunserver_ip.setEnabled(false);
			tv_tunclient_port_number.setEnabled(false);
			cb_overtake_default_route.setEnabled(false);
			vpnButton.setText(R.string.stop_vpn);
		}
	}
}
