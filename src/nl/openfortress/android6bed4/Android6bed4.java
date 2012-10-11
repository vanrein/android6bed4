package nl.openfortress.android6bed4;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.net.VpnService.Builder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.widget.ProgressBar;

import java.io.IOException;
import java.net.*;


public class Android6bed4 extends Activity {

	private DatagramSocket uplink = null;
	private TunnelService downlink = null;
	
	private ProgressBar debug;
	
	public InetSocketAddress publicserver;
	
	/*
	 * State management interface, called from Android OS.  The onXXX() conform to a state diagram.
	 * 
	 * @see http://developer.android.com/reference/android/app/Activity.html#SavingPersistentState
	 */
	protected void onCreate (Bundle savedInstanceState) {
		super.onCreate (savedInstanceState);
		try {
			publicserver = new InetSocketAddress (Inet4Address.getByName ("145.136.0.1"), 25788);
		} catch (UnknownHostException uhe) {
			publicserver = null;
		}
		setContentView (R.layout.user_interface);
		debug = (ProgressBar) findViewById (R.id.progress_bar);
		
	}
	
	protected void onStart () {
		super.onStart ();
		if (debug != null) debug.setProgress (10);
		try {
			uplink = new DatagramSocket ();
		} catch (SocketException se) {
			uplink = null;
		}
		try {
			setupTunnelService ((Inet6Address) Inet6Address.getByName ("::1"));	/* TODO: Note here, not with a static IPv6 address */
		} catch (UnknownHostException uhe) {
		}
		if (debug != null) debug.setProgress (20);
		if (debug != null) debug.setProgress (30);
	}
	
	protected void onResume () {
		super.onResume ();
		/* TODO: Check if the IPv6 address is still the same?  Also run after onStart! */
		if (debug != null) debug.setProgress (50);
	}

	protected void onPause () {
		super.onPause ();
		/* TODO: Nothing to do I suppose... try to avoid loosing IPv6 address? */
		if (debug != null) debug.setProgress (70);		
	}
	
	protected void onStop () {
		super.onStop ();
		if (uplink != null) {
			uplink.close ();
			uplink = null;
		}
		if (debug != null) debug.setProgress (80);
	}

	protected void onRestart () {
		super.onRestart ();
		/* TODO: Nothing? */
		if (debug != null) debug.setProgress (90);
	}
	
	protected void onDestroy () {
		super.onDestroy ();
		if (downlink != null) {
			downlink.teardown ();
			downlink = null;
		}
		/* TODO: Nothing? */
		if (debug != null) debug.setProgress (100);
 	}


	/***                                                ***
	 ***   Internal affairs -- tunnel service setup.    ***
	 ***                                                ***/

	public void setupTunnelService (Inet6Address addr6bed4) {
		//
		// Prepare the context for the VPN
		Intent mktun_intent = VpnService.prepare (this);
		if (mktun_intent != null) {
			// This is a new start of the VPN
			this.startActivityForResult (mktun_intent, 0);
		} else {
			// Already started, apparently OK, so proceed to startup
			this.onActivityResult (0, Activity.RESULT_OK, null);
		}
	}
		
	synchronized protected void onActivityResult (int reqcode, int resultcode, Intent data) {
		if (resultcode == Activity.RESULT_OK) {
			//
			// Cleanup any prior tunnel file descriptors
			if (downlink != null) {
				downlink.teardown ();
			}
			//
			// Setup a new tunnel
			// TODO: Due to this statement, two tunnel interfaces get created;
			//       without it, none are created.  Not sure what to think
			//       of it... need to leave it like this for now.
			downlink = new TunnelService (uplink, publicserver);
			if (downlink != null) {
				downlink = new TunnelService (); /*TODO:HUH?*/
				//
				// Given the successful startup, avoid future cleanups so the
				// TunnelService can continue to use these resources.  It has
				// taken responsibility over their cleanip after returning.
				uplink = null;
				downlink = null;
				publicserver = null;
			}
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
