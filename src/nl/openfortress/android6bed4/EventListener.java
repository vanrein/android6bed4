
package nl.openfortress.android6bed4;


import java.net.NetworkInterface;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.Collection;
import java.util.Vector;


import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.Context;
import android.net.ConnectivityManager;


/* The EventListener class picks up signals from the Android OS,
 * and processes them internally.
 * 
 * Upon receiving BOOT_COMPLETED, it will see if the user wishes
 * to start 6bed4 at boot time. Since this presents a popup, this
 * is a setting that must be explicitly configured.  More details:
 * http://stackoverflow.com/questions/5051687/broadcastreceiver-not-receiving-boot-completed
 * 
 * Upon receiving CONNECTIVITY_ACTION, it will see if there is a
 * default route over IPv6 that does not a 6bed4 address.  See:
 * http://stackoverflow.com/questions/5276032/connectivity-action-intent-recieved-twice-when-wifi-connected
 * http://developer.android.com/reference/android/net/ConnectivityManager.html 
 */
class EventListener extends BroadcastReceiver {
	
	private TunnelService netcfg_target;
	
	public void onReceive (Context ctx, Intent intent) {
		String act = intent.getAction ();
		
		if (act.equals (ConnectivityManager.CONNECTIVITY_ACTION)) {
			//
			// The Network Configuration has changed.
			// The NetworkInfo for the affected network is sent as an extra
			if (netcfg_target == null) {
				return;
			}
			try {
				Collection <byte[]> ipv6list = new Vector <byte[]> ();
				Enumeration <NetworkInterface> nif_iter = NetworkInterface.getNetworkInterfaces ();
				while (nif_iter.hasMoreElements ()) { 
					NetworkInterface nif = nif_iter.nextElement ();
					try {
						if (nif.isUp ()) {
							Enumeration <InetAddress> adr_iter = nif.getInetAddresses ();
							while (adr_iter.hasMoreElements ()) {
								InetAddress adr = adr_iter.nextElement ();
								if (adr.isLoopbackAddress () || adr.isLinkLocalAddress () || adr.isAnyLocalAddress () || adr.isSiteLocalAddress () || adr.isMulticastAddress ()) {
									continue;
								}
								byte ipv6addr [] = adr.getAddress ();
								if (ipv6addr.length != 16) {
									continue;
								}
								ipv6list.add (ipv6addr);
							}
						}
					} catch (SocketException se) {
						;	/* Ignore */
					}
					// Now tell the tunnel service about ipv6list
					netcfg_target.notify_ipv6_addresses (ipv6list);
				}
			} catch (SocketException se) {
				;	/* Ignore */
			}
			
		} else if (act.equals (Intent.ACTION_BOOT_COMPLETED)) {
			//
			// The system has finished booting.  Consider starting 6bed4
			// at this point (if the user is willing to deal with the popup
			// from the VPN toolkit at this point.
			Intent main = new Intent (Intent.ACTION_MAIN);
			main.addCategory (Intent.CATEGORY_HOME);
			ctx.startActivity (main);
		}
		
	}
	
	/* Register this object as a network monitor reporting to the
	 * given TunnelService object when the set of local IPv6 addresses
	 * changes.  Note that the TunnelService is supposed to be smart
	 * enough to avoid endless loops; the monitor simply reports if a
	 * notification comes in, even if it is caused by 6bed4.
	 */
	public void register_network_monitor (TunnelService target) {
		if (netcfg_target != null) {
			unregister_network_monitor ();
		}
		netcfg_target = target;
		IntentFilter intflt = new IntentFilter (Context.CONNECTIVITY_SERVICE);
		/* TODO -- how to get to the current context?!?
		Context ctx = getApplicationContext ();
		ctx.registerReceiver (this, intflt);
		*/

	}
	
	/* Unregister this object as a network monitor reporting to a
	 * once-setup TunnelService.
	 */
	public void unregister_network_monitor () {
		//TODO// how to unregister for CONNECTIVITY_SERVICE broadcasts?
		netcfg_target = null;
	}
	
}