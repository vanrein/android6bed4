package nl.openfortress.android6bed4;

import android.app.Activity;
import android.content.Intent;
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
	
	private Intent mktun_intent;

	private boolean interactive = false;

	private int local_port = 0;
	
	
	/*
	 * User interface, with method names as specified in the XML file layout/user_interface.xml
	 */
	
	/*
	 * User interface: onPersistClick is called when the checkbox is toggled that changes reboot behaviour.
	 */
	public void onPersistClick (View vw) {
		persist = ((Switch) vw).isChecked ();
		teardown_tunnel ();
		if (!persist) {
			//
			// Switching off the boot persistency is done immediately
			//TODO// Store "persistent := false"
		} else {
			//
			// Skip:
			// Switching on boot persistency is only done after success
		}
	}

	/*
	 * User interface: onDefaultRouteClick is called when the switch is toggled between a /64 and a /0 prefix.
	 */
	public void onDefaultRouteClick (View vw) {
		boolean defaultroute = ((Switch) vw).isChecked ();
		teardown_tunnel ();
	}
	
	/*
	 * User interface: onEnablerClick is called when the switch is toggled that enables/disables the tunnel.
	 */
	public void onEnablerClick (View vw) {
		boolean enable = ((Switch) vw).isChecked ();
		teardown_tunnel ();
		if (enable) {
			//
			// Enable the 6bed4 tunnel
			try {
				TextView tv = (TextView) findViewById (R.id.tunserver_ip_string);
				String pubsrv_ip = tv.getText ().toString ().trim ();
				if (pubsrv_ip.length () == 0) {
					pubsrv_ip = "145.136.0.1";
				}
				publicserver = new InetSocketAddress (Inet4Address.getByName (pubsrv_ip), 25788);
			} catch (Throwable thr) {
				publicserver = null;
				Switch sv = (Switch) findViewById (R.id.enable_6bed4);
				sv.setChecked (false);
				return;
			}
			//
			// Extract local UDP port from GUI (or already have it setup, for better recycling)
			int new_port;
			try {
				TextView tv = (TextView) findViewById (R.id.tunclient_port_number);
				new_port = new Integer (tv.getText ().toString ().trim ()).intValue () & 0xfffe;
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
			} catch (SocketException se) {
				uplink = null;
				Switch sv = (Switch) findViewById (R.id.enable_6bed4);
				sv.setChecked (false);
				return;
			}
			try {
				setup_tunnel ((Inet6Address) Inet6Address.getByName ("::1"));	/* TODO: Note here, not with a static IPv6 address */
				if (!interactive) {
					finish ();
				}
			} catch (UnknownHostException uhe) {
				Switch sv = (Switch) findViewById (R.id.enable_6bed4);
				sv.setChecked (false);
				return;
			}
			//TODO// Wait for 1s or so, reporting the state?  Or link back from tunsvc to view?
		}
	}
	
	/*
	 * User interface: Change to either text field -- meaning, disable any current tunnel (and the enabler widget).
	 */
	public void afterTextChanged (Editable s) { ; }
	public void onTextChanged (CharSequence s, int start, int count, int after) { ; }
	public void beforeTextChanged (CharSequence s, int start, int count, int after) {
		teardown_tunnel ();
	}
	
	/*
	 * State management interface, called from Android OS.  Thee onXXX() conform to a state diagram.
	 * 
	 * @see http://developer.android.com/reference/android/app/Activity.html#SavingPersistentState
	 */
	protected void onCreate (Bundle savedInstanceState) {
		super.onCreate (savedInstanceState);
		TunnelService tunsvc = TunnelService.theTunnelService ();
		if (tunsvc != null) {
			uplink = tunsvc.uplink;
		}
		try {
			uplink = new DatagramSocket ();	//TODO// Remove port (to unfix it)
		} catch (IOException ioe) {
			uplink = null;
		}
		mktun_intent = VpnService.prepare (this);
		boolean have_tunnel = (TunnelService.theTunnelService () != null);
		interactive = (mktun_intent == null) || (tunsvc != null);
		//TODO:USE?// savedInstanceState.getBoolean ("persist_accross_reboots", false);
		//TODO:USE?// savedInstanceState.getBoolean ("overtake_default_route", true);
		//TODO:USE?// savedInstanceState.getString ("tunserver_ip", "145.136.0.1");
		//TODO:USE?// savedInstanceState.getInt ("tunclient_port", 0);
		TextView tv;
		Switch sv;
		setContentView (R.layout.user_interface);
		tv = (TextView) findViewById (R.id.tunserver_ip_string);
		tv.addTextChangedListener (this);
		tv = (TextView) findViewById (R.id.tunclient_port_number);
		tv.addTextChangedListener (this);
		sv = (Switch) findViewById (R.id.enable_6bed4);
		sv.setChecked (have_tunnel);
		sv = (Switch) findViewById (R.id.overtake_default_route);
		sv.setChecked (true);
		sv = (Switch) findViewById (R.id.persist_accross_reboots);
		sv.setChecked (false);
	}
	
	protected void onStart () {
		super.onStart ();
		if (!interactive) {
			//
			// This is a new start of the VPN
			try {
				//TODO// extract public server address from params 
				publicserver = new InetSocketAddress (Inet4Address.getByName ("145.136.0.1"), 25788); /* TODO:FIXED */
			} catch (UnknownHostException uhe) {
				publicserver = null;
			}
			try {
				setup_tunnel ((Inet6Address) Inet6Address.getByName ("::1"));	/* TODO: Note here, not with a static IPv6 address */
			} catch (UnknownHostException uhe) {
				interactive = true;
			}
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

	public void setup_tunnel (Inet6Address addr6bed4) {
		//
		// Prepare the context for the VPN
		if (mktun_intent == null) {
			mktun_intent = VpnService.prepare (this);
		}
		//TODO:ALT// Intent mktun_intent = TunnelService.prepare (this);
		if (mktun_intent != null) {
			// This is a new start of the VPN
			this.startActivityForResult (mktun_intent, 0);
			mktun_intent = null;
		} else {
			// Already started, apparently OK, so proceed to startup
			this.onActivityResult (0, Activity.RESULT_OK, null);
		}
	}
		
	synchronized protected void onActivityResult (int reqcode, int resultcode, Intent data) {
		if (resultcode == Activity.RESULT_OK) {
			//
			// Cleanup any prior tunnel file descriptors
			teardown_tunnel ();
			//
			// Setup a new tunnel
			// TODO: Due to this statement, two tunnel interfaces get created;
			//       without it, none are created.  Not sure what to think
			//       of it... need to leave it like this for now.
			TunnelService downlink = new TunnelService (uplink, publicserver);
			if (downlink != null) {
				downlink = new TunnelService (); /*TODO:HUH?*/
			}
			if (!interactive) {
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
