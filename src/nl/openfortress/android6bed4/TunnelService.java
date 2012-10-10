package nl.openfortress.android6bed4;

import android.net.VpnService;
import android.os.ParcelFileDescriptor;

import java.net.*;
import java.io.*;


import nl.openfortress.android6bed4.FixedLocalSettings;
	/* 
	 * This import will fail when building 0.3 or other early versions
	 * from git.  The reason is that a file is needed that defines the
	 * following:
	 	
	 	package nl.openfortress.android6bed4;
	 	class FixedLocalSettings {
	 		static final String public_ipv4 = "123.45.67.89";
	 		static final int public_udp_port = 45678;
	 	};
	 	
	 * You should of course replace with your own settings.  Note that
	 * the public_udp_port is not wholly reliable until you setup port
	 * forwarding for it.
	 * 
	 * This kludge exists for testing purposes only, of course.  It
	 * makes it possible to test the setup without publicly sharing
	 * your IPv4 address and having it bashed by the rest of the
	 * experimental World ;-)
	 * 
	 * Welcome to the pain of early development bootstrapping ;-)
	 * 
	 */


public final class TunnelService extends VpnService {

	static private ParcelFileDescriptor fio = null;
	static private FileInputStream  downlink_rd = null;
	static private FileOutputStream downlink_wr = null;

	static private SocketAddress tunserver = null;
	static private DatagramSocket uplink = null;
	
	public TunnelService () {
		//
		// Called by the system, to startup a service
		synchronized (this) {
			while ((downlink_rd == null) || (downlink_wr == null) || (uplink == null) || (tunserver == null)) {
				try {
					wait ();
				} catch (InterruptedException ie) {
					;
				}
			}
		}
		Thread cp = new CopyShop ();
		cp.start ();
	}
	
	private class CopyShop extends Thread {
		public void run () {
			byte packet_up [] = new byte [1280];
			DatagramPacket packet_dn = new DatagramPacket (new byte [1280+28], 1280+28);
			try {
				while (true) {
					int uplen = downlink_rd.read (packet_up);
					if (uplen > 0) {
						uplink.send (new DatagramPacket (packet_up, uplen, tunserver));
					}
					uplink.receive (packet_dn);
					int dnlen = packet_dn.getLength ();
					if (dnlen > 0) {
						downlink_wr.write (packet_dn.getData (), 0, dnlen);
					}
					if (uplen + dnlen == 0) {
						try {
							sleep (10);
						} catch (InterruptedException ie) {
							;	// Great, let's move on!
						}
					}
				}
			} catch (IOException ioe) {
				throw new RuntimeException ("I/O failure", ioe);
			}
		}
	}
	
	private class Downlink extends Thread {
		;
	}
	
	public TunnelService (DatagramSocket uplink_socket, SocketAddress publicserver) {
		//
		// Create a VPN builder object
		Builder builder;
		builder = new Builder ();
		builder.setSession ("6bed4 uplink to IPv6");
		builder.setMtu (1280);
		try {
			byte ext_ip4 [] = Inet4Address.getByName (FixedLocalSettings.public_ipv4).getAddress ();
			byte ext_udpH = (byte) (FixedLocalSettings.public_udp_port >> 8  );
			byte ext_udpL = (byte) (FixedLocalSettings.public_udp_port & 0xff);
			byte fixed_address [] = new byte [16];
			fixed_address [ 0] = (byte) 0x20; // RIPE-assigned prefix EXPIRING IN 1 YEAR
			fixed_address [ 1] = (byte) 0x01;
			fixed_address [ 2] = (byte) 0x06;
			fixed_address [ 3] = (byte) 0x7c;
			fixed_address [ 4] = (byte) 0x12;
			fixed_address [ 5] = (byte) 0x7c;
			fixed_address [ 6] = (byte) 0x00;
			fixed_address [ 7] = (byte) 0x00;
			fixed_address [ 8] = (byte) (ext_udpL ^ 0x02);
			fixed_address [ 9] = ext_udpH;
			fixed_address [10] = ext_ip4 [0];
			fixed_address [11] = (byte) 0xff;
			fixed_address [12] = (byte) 0xfe;
			fixed_address [13] = ext_ip4 [1];
			fixed_address [14] = ext_ip4 [2];
			fixed_address [15] = ext_ip4 [3];
			builder.addAddress (Inet6Address.getByAddress (fixed_address), 64);
		} catch (UnknownHostException uhe) {
			;
		}
//		builder.addRoute ("2001:67c:127c::", 64);
		builder.addRoute ("::", 0);
		fio = builder.establish ();
		synchronized (this) {
			downlink_rd = new FileInputStream  (fio.getFileDescriptor ());
			downlink_wr = new FileOutputStream (fio.getFileDescriptor ());
			uplink = uplink_socket;
			tunserver = publicserver;
			notifyAll ();
		}
	}
	
	synchronized public void teardown () {
		try {
			if (downlink_rd != null) {
				downlink_rd.close ();
				downlink_rd = null;
			}
			if (downlink_wr != null) {
				downlink_wr.close ();
				downlink_wr = null;
			}
			if (fio != null) {
				fio.close ();
				fio = null;
			}
		} catch (IOException ioe) {
			;
		}
	}
	
	synchronized public void onRevoke () {
		this.teardown ();
	}
	
}
