package nl.openfortress.android6bed4;

import android.net.VpnService;
import android.app.Activity;
import android.content.Intent;
import android.os.ParcelFileDescriptor;

import java.net.*;
import java.io.IOException;


public final class TunnelService extends VpnService {

	ParcelFileDescriptor fio = null;

	public TunnelService (Activity act, Inet6Address addr6bed4) {
		//
		// Prepare the context for the VPN
		Intent mktun_intent = super.prepare (act);
		if (mktun_intent != null) {
			// This is a new start of the VPN
			act.startActivityForResult (mktun_intent, 0);
		} else {
			// Already started, apparently OK, so proceed to startup
			this.onActivityResult (0, Activity.RESULT_OK, null);
		}
	}
		
	synchronized protected void onActivityResult (int reqcode, int resultcode, Intent data) {
		if (resultcode == Activity.RESULT_OK) {
			//
			// Cleanup any prior tunnel file descriptors
			this.onRevoke ();
			//
			// Create a VPN builder object
			Builder builder;
			builder = new Builder ();
			builder.setSession ("6bed4 uplink to IPv6");
			builder.setMtu (1280);
			try {
				builder.addAddress (Inet6Address.getByName ("fe80:6:bed:4::"), 64);
			} catch (UnknownHostException uhe) {
				;
			}
			builder.addRoute ("::", 0);
			fio = builder.establish ();
		}
	}
	
	synchronized public void onRevoke () {
		if (fio != null) {
			try {
				fio.close ();
			} catch (IOException ioe) {
				;
			}
			fio = null;
		}
	}

	public TunnelService () {
/*NOTHERE		
		//
		// Create a VPN builder object
		Builder builder;
		builder = new Builder ();
		builder.setSession ("6bed4 uplink to IPv6");
		builder.setMtu (1281);
		try {
			builder.addAddress (Inet6Address.getByName ("fe80:6:bed:4::"), 64);
		} catch (UnknownHostException uhe) {
			;
		}
		builder.addRoute ("::", 0);
		fio = builder.establish ();
*/
	}

}
