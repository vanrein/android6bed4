package nl.openfortress.android6bed4;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.net.VpnService.Builder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.text.TextWatcher;
import android.text.Editable;
import android.widget.TextView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Switch;

import java.io.IOException;
import java.net.*;


public class Android6bed4 extends Activity implements TextWatcher {

	private DatagramSocket uplink = null;
		
	private boolean persist = false;
	
	public InetSocketAddress publicserver;
	
	private boolean am_booting = false;

	private int local_port = 0;
	
	private SharedPreferences prefs;
	
	private static boolean approved = true;
	
	/*
	 * User interface, with method names as specified in the XML file layout/user_interface.xml
	 */

		
	/*
	 * User interface: onPersistClick is called when the checkbox is toggled that changes reboot behaviour.
	 */
	public void onPersistClick (View vw) {
		persist = ((Switch) vw).isChecked ();
		SharedPreferences.Editor ed = prefs.edit ();
		ed.putBoolean ("persist_accross_reboots", persist);
		ed.commit ();
	}

	/*
	 * User interface: onDefaultRouteClick is called when the switch is toggled between a /64 and a /0 prefix.
	 */
	public void onDefaultRouteClick (View vw) {
		boolean defaultroute = ((Switch) vw).isChecked ();
		set_enabler (false);
		SharedPreferences.Editor ed = prefs.edit ();
		ed.putBoolean ("overtake_default_route", defaultroute);
		ed.commit ();
	}

	/*
	 * Set a given value for the enabler switch -- both visibly and internally.
	 */
	public void set_enabler (boolean newsetting) {
		if (prefs.getBoolean ("enable_6bed4", !newsetting) != newsetting) {
			Switch enabler = (Switch) findViewById (R.id.enable_6bed4);
			enabler.setChecked (newsetting);
			onEnablerClick (enabler);
		}
	}

	
	/*
	 * User interface: onEnablerClick is called when the switch is toggled that enables/disables the tunnel.
	 */
	public void onEnablerClick (View vw) {
		boolean enable = ((Switch) vw).isChecked ();
		SharedPreferences.Editor ed;
		if (enable) {
			TextView tv;
			//
			// Check that the tunnel server IP address parses
			String pubsrv_ip;
			tv = null;
			try {
				tv = (TextView) findViewById (R.id.tunserver_ip_string);
				pubsrv_ip = tv.getText ().toString ().trim ();
				if (pubsrv_ip.length () > 0) {
					InetAddress test = Inet4Address.getByName (pubsrv_ip);
				} else {
					pubsrv_ip = "145.136.0.1";
				}
			} catch (Throwable thr) {
				pubsrv_ip = "145.136.0.1";
			}
			//TODO:COUPLEBACK// tv.setText (pubsrv_ip.subSequence (0, pubsrv_ip.length ()));
			if (!pubsrv_ip.equals (prefs.getString ("tunserver_ip", "INVALID"))) {
				ed = prefs.edit ();
				ed.putString ("tunserver_ip", pubsrv_ip);
				ed.commit ();
			}
			//
			// Check that the local port number is valid
			String portstr;
			int new_port;
			tv = null;
			try {
				tv = (TextView) findViewById (R.id.tunclient_port_number);
				portstr = tv.getText ().toString ().trim ();
				new_port = Integer.valueOf (portstr);
			} catch (Throwable thr) {
				portstr = "";
				new_port = local_port;
			}
			if ((new_port <= 0) || (new_port > 65535) || ((new_port & 0x0001) != 0x0000)) {
				new_port = 0;
				portstr = "";
			}
			if (tv != null) {
				//TODO:COUPLEBACK// tv.setText (portstr.subSequence (0, portstr.length ()));
			}
			if (prefs.getInt ("tunclient_port", -1) != new_port) {
				ed = prefs.edit ();
				ed.putInt ("tunclient_port", new_port);
				ed.commit ();
			}
			//
			// Enable the 6bed4 tunnel
/* TODO: MOVED TO TUNNELSERVICE
			try {
				TextView tv = (TextView) findViewById (R.id.tunserver_ip_string);
				String pubsrv_ip = tv.getText ().toString ().trim ();
				if (pubsrv_ip.length () == 0) {
					pubsrv_ip = "145.136.0.1";
				}
				publicserver = new InetSocketAddress (Inet4Address.getByName (pubsrv_ip), 25788);
				SharedPreferences.Editor ed = prefs.edit ();
				ed.putString ("tunserver_ip", pubsrv_ip);
				ed.commit ();
			} catch (Throwable thr) {
				publicserver = null;
				Switch sv = (Switch) findViewById (R.id.enable_6bed4);
				sv.setChecked (false);
				return;
			}
			//
			// Extract local UDP port from GUI
			int new_port;
			try {
				TextView tv = (TextView) findViewById (R.id.tunclient_port_number);
				new_port = Integer.valueOf (tv.getText ().toString ().trim ());
			} catch (Throwable thr) {
				new_port = local_port;
			}
			try {
				DatagramSocket new_uplink;
				if ((new_port > 0) && (new_port <= 65535) && ((new_port & 0x0001) == 0x0000)) {
					new_uplink = new DatagramSocket (new_port);
				} else {
					new_uplink = new DatagramSocket ();
					new_port = new_uplink.getPort ();
				}
				if (uplink != null) {
					uplink.close ();
				}
				local_port = new_port;
				uplink = new_uplink;
				SharedPreferences.Editor ed = prefs.edit ();
				ed.putInt ("tunclient_port", new_port);
				ed.commit ();
			} catch (SocketException se) {
				uplink = null;
				Switch sv = (Switch) findViewById (R.id.enable_6bed4);
				sv.setChecked (false);
				return;
			}
*/
			//
			// Actually create the tunnel if it is new
			if (TunnelService.theTunnelService () == null) {
				setup_tunnel ();
				//
				//TODO// Hack: This function is called recursively from onActivityResult inside setup_tunnel
				//             The reason?  The use of synchronized() inside onActivityResult makes it be skipped
				return;
			}
		}
		//
		// Change the setting of the enabling flag, to be picked up by the tunnel service
		ed = prefs.edit ();
		ed.putBoolean ("enable_6bed4", enable);
		ed.commit ();
		//TODO// Wait for 1s or so, reporting the state?  Or link back from tunsvc to view?
	}
	
	/*
	 * User interface: Change to either text field -- meaning, disable any current tunnel (and the enabler widget).
	 */
	public void onTextChanged (CharSequence s, int start, int count, int after) { ; }
	public void beforeTextChanged (CharSequence s, int start, int count, int after) {
		set_enabler (false);
	}
	public void afterTextChanged (Editable s) {
/*TODO:NOT:ON:EVERY:KEYSTROKE
		TextView tv;
		SharedPreferences.Editor ed;
		ed = prefs.edit ();
		//
		// Check that the tunnel server IP address parses
		String pubsrv_ip;
		tv = null;
		try {
			tv = (TextView) findViewById (R.id.tunserver_ip_string);
			pubsrv_ip = tv.getText ().toString ().trim ();
			if (pubsrv_ip.length () > 0) {
				InetAddress test = Inet4Address.getByName (pubsrv_ip);
			} else {
				pubsrv_ip = "145.136.0.1";
			}
		} catch (Throwable thr) {
			pubsrv_ip = "145.136.0.1";
		}
		//TODO:COUPLEBACK// tv.setText (pubsrv_ip.subSequence (0, pubsrv_ip.length ()));
		ed.putString ("tunserver_ip", pubsrv_ip);
		//
		// Check that the local port number is valid
		String portstr;
		int new_port;
		tv = null;
		try {
			tv = (TextView) findViewById (R.id.tunclient_port_number);
			portstr = tv.getText ().toString ().trim ();
			new_port = Integer.valueOf (portstr);
		} catch (Throwable thr) {
			portstr = "";
			new_port = local_port;
		}
		if ((new_port <= 0) || (new_port > 65535) || ((new_port & 0x0001) != 0x0000)) {
			new_port = 0;
			portstr = "";
		}
		if (tv != null) {
			//TODO:COUPLEBACK// tv.setText (portstr.subSequence (0, portstr.length ()));
		}
		ed.putInt ("tunclient_port", new_port);
		ed.commit ();
*/
	}
	
	/*
	 * State management interface, called from Android OS.  Thee onXXX() conform to a state diagram.
	 * 
	 * @see http://developer.android.com/reference/android/app/Activity.html#SavingPersistentState
	 */
	protected void onCreate (Bundle savedInstanceState) {
		super.onCreate (savedInstanceState);
		prefs = getPreferences (MODE_PRIVATE);
		am_booting = false;
		Bundle extras = getIntent ().getExtras ();
		if ((extras != null) && (extras.getBoolean ("am_booting", false))) {
			am_booting = true;
			if (!prefs.getBoolean ("persist_accross_reboots", false)) {
				finish ();
			}
		}
/* TODO: MOVED TO TUNNELSERVICE AS PREFERENCE PROCESSING
		TunnelService tunsvc = TunnelService.theTunnelService ();
		if (tunsvc != null) {
			uplink = tunsvc.uplink;
		}
		try {
			int port = prefs.getInt ("tunclient_port", 0);
			if ((port > 0) && (port <= 65535) && ((port & 0x0001) == 0x0000)) {
				uplink = new DatagramSocket (port);
			} else {
				uplink = new DatagramSocket ();
			}
		} catch (IOException ioe) {
			uplink = null;
		}
*/
		boolean have_tunnel = TunnelService.isRunning ();
		// App started -> actually show the GUI
		TextView tv;
		Switch sv;
		setContentView (R.layout.user_interface);
		tv = (TextView) findViewById (R.id.tunserver_ip_string);
		String srv_ip = prefs.getString ("tunserver_ip", "");
		if (srv_ip.equals ("145.136.0.1")) {
			srv_ip = "";
		}
		tv.setText (srv_ip);
		tv.addTextChangedListener (this);
		tv = (TextView) findViewById (R.id.tunclient_port_number);
		int port = prefs.getInt ("tunclient_port", 0);
		if ((port > 0) && (port <= 65535) && ((port & 0x0001) == 0x0000)) {
			tv.setText (Integer.toString (port));
		} else {
			tv.setText ("");
		}
		tv.addTextChangedListener (this);
		sv = (Switch) findViewById (R.id.enable_6bed4);
		sv.setChecked (have_tunnel);
		SharedPreferences.Editor ed = prefs.edit ();
		ed.putBoolean ("enable_6bed4", have_tunnel);
		ed.commit ();
		sv = (Switch) findViewById (R.id.overtake_default_route);
		sv.setChecked (prefs.getBoolean ("overtake_default_route", true));
		sv = (Switch) findViewById (R.id.persist_accross_reboots);
		sv.setChecked (prefs.getBoolean ("persist_accross_reboots", false));
	}
	
	protected void onStart () {
		super.onStart ();
		if (am_booting) {
			//
			// This is a new start of the VPN
/* TODO: REMOVE OLD-STYLE TUNNEL START
			try {
				//TODO// extract public server address from params 
				publicserver = new InetSocketAddress (Inet4Address.getByName (prefs.getString ("tunserver_ip", "145.136.0.1")), 25788);
			} catch (UnknownHostException uhe) {
				publicserver = null;
			}
			setup_tunnel ();
*/
			//
			// Start the VPN at boot time by "virtually clicking" on the enabler button
			set_enabler (true);
		}
	}
	
	protected void onResume () {
		super.onResume ();
		/* TODO: Check if the IPv6 address is still the same?  Also run after onStart! */
	}

	protected void onPause () {
		super.onPause ();
		/* TODO: Nothing to do I suppose... try to avoid loosing IPv6 address? */
	}
	
	protected void onStop () {
		super.onStop ();
	}

	protected void onRestart () {
		super.onRestart ();
	}
	
	protected void onDestroy () {
		super.onDestroy ();
 	}


	/***                                                ***
	 ***   Internal affairs -- tunnel service setup.    ***
	 ***                                                ***/

	public void setup_tunnel () {
		//
		// Prepare the context for the VPN
		Intent mktun_intent = VpnService.prepare (this);
		//TODO:ALT// Intent mktun_intent = TunnelService.prepare (this);
		if (mktun_intent != null) {
			// This is a newly authenticated start of the VPNService / tunnel
			approved = false;
			startActivityForResult (mktun_intent, 0);
			/* TODO: BEAUTIFUL, BUT FAILING CODE -- ANDROID API COCKUP
			synchronized (this) {
				while (!approved) {
					try {
						wait ();
					} catch (InterruptedException ie) {
						throw new RuntimeException ("Interrupted", ie);
					}
				}
			}
			*/
		} else {
			// Already started, apparently OK, so proceed to startup
			onActivityResult (0, Activity.RESULT_OK, null);
		}
	}
		
	protected void onActivityResult (int reqcode, int resultcode, Intent data) {
		if (resultcode == Activity.RESULT_OK) {
			//
			// Cleanup any prior tunnel file descriptors
			teardown_tunnel ();
			//
			// Setup a new tunnel
			// TODO: Due to this statement, two tunnel interfaces get created;
			//       without it, none are created.  Not sure what to think
			//       of it... need to leave it like this for now.
/*TODO: Only instantiate
			TunnelService downlink = new TunnelService (uplink, publicserver);
			if (downlink != null) {
				downlink = new TunnelService (); //TODO:HUH?//
			}
*/
			TunnelService downlink = new TunnelService ();
			prefs.registerOnSharedPreferenceChangeListener (downlink);
/* TODO: BEAUTIFUL CODE THAT DOES NOT WORK DUE TO CRAPPY API
   			synchronized (this) {
				approved = true;
				notifyAll ();
			}
 */
			set_enabler (true);
			if (am_booting) {
				finish ();
			}
		} else {
			//
			// Result is not OK -- tear down the tunnel
			teardown_tunnel ();
		}
	}

	private void teardown_tunnel () {
		TunnelService tunsvc = TunnelService.theTunnelService ();
		if (tunsvc != null) {
			tunsvc.teardown ();
			Switch enabler = (Switch) findViewById (R.id.enable_6bed4);
			enabler.setChecked (false);
			prefs.unregisterOnSharedPreferenceChangeListener (tunsvc);
		}
	}
	
	/*
	synchronized public void onRevoke () {
		if (downlink != null) {
			downlink.teardown ();
		}
	}
	*/

}
