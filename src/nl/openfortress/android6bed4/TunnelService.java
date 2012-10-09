package nl.openfortress.android6bed4;

import android.net.VpnService;
import android.app.Activity;
import android.content.Intent;
import android.os.ParcelFileDescriptor;

import java.net.*;
import java.io.IOException;


public final class TunnelService extends VpnService {

	private ParcelFileDescriptor fio = null;

	public TunnelService () {
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
	}
	
	synchronized public void teardown () {
		if (fio != null) {
			try {
				fio.close ();
			} catch (IOException ioe) {
				;
			}
			fio = null;
		}
	}

}
