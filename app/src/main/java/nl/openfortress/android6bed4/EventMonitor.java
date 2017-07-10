
package nl.openfortress.android6bed4;


import java.net.Inet6Address;
import java.net.NetworkInterface;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.Collection;
import java.util.Vector;


import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.Context;
import android.content.ComponentName;
import android.net.ConnectivityManager;
import android.net.VpnService;
import android.util.Log;


/* The EventMonitor class picks up signals from the Android OS,
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
public class EventMonitor extends BroadcastReceiver {
	
	public final static String TAG = "android6bed4.EventMonitor";
	
	public void onReceive (Context ctx, Intent intent) {
		String act = intent.getAction ();
		TunnelService tunsvc = TunnelService.theTunnelService ();
	
/* TODO: MALFUNCTIONING NETWORK ADDRESS PICKUPS?  FOR NOW, MANUAL SETUP OF DEFAULTROUTE (UNAVAILABLE DATA IN ANDROID)
		if (act.equals (ConnectivityManager.CONNECTIVITY_ACTION)) {
			//
			// The Network Configuration has changed.
			// The NetworkInfo for the affected network is sent as an extra
			if (tunsvc == null) {
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
								Log.v (TAG, "Tunnel Service notified of IPv6 address list");
							}
						}
					} catch (SocketException se) {
						;	// Ignore
					}
					// Now tell the tunnel service about ipv6list
					tunsvc.notify_ipv6_addresses (ipv6list);
				}
			} catch (SocketException se) {
				;	// Ignore
			}
			Log.v (TAG, "Network Interface Change Processed");
			
		} else 
*/
		if (act.equals (Intent.ACTION_BOOT_COMPLETED)) {
			//
			// The system has finished booting.  Consider starting 6bed4
			// at this point (if the user is willing to deal with the popup
			// from the VPN toolkit at this point.
			Intent main = new Intent (Intent.ACTION_MAIN);
			main.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			// main.addCategory (Intent.CATEGORY_HOME);
			main.addCategory (Intent.CATEGORY_LAUNCHER);
			main.setComponent (new ComponentName (ctx, Android6bed4.class));
			main.putExtra ("am_booting", true);
			ctx.startActivity (main);
			Log.v (TAG, "Boot action completed");
		}
		
	}

}