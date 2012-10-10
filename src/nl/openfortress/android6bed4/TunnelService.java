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

	static private InetSocketAddress tunserver = null;
	static private DatagramSocket uplink = null;

	static private KeepAliveService kas = null;
	
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
				/* 
				 * I am aware that the following code is less than optimal!
				 * For some reason, the VpnService only returns a non-blocking
				 * file descriptor that cannot subsequently be altered to be
				 * blocking (for two parallel Threads) or used in a select()
				 * style.  So polling is required.
				 * The uplink is blocking, just to continue the confusion.
				 * A slight upgrade of performance might be achieved by setting
				 * up a separate Thread to handle that, but given that polling
				 * is needed anyway, I decided not to bother to get the best out
				 * of what seems to me like a bad design choice in VpnService.
				 * If I have missed a way to turn the VpnService interface into
				 * one that works in select() or even just a blocking version
				 * than *please* let me know!  IMHO, polling is a rude technique.
				 * 
				 * Rick van Rein, October 2012.
				 */
				while (true) {
					int uplen, dnlen;
					synchronized (this) {
						if ((downlink_rd == null) || (uplink == null)) {
							break;
						}
						uplen = downlink_rd.read (packet_up);
						if (uplen > 0) {
							try {
								uplink.send (new DatagramPacket (packet_up, uplen, tunserver));
							} catch (IOException ioe) {
								/* Assume a spurious error e.g. WiFi jumpiness */ ;
							}
						}
					}
					synchronized (this) {
						if ((uplink == null) || (downlink_wr == null)) {
							break;
						}
						try {
							uplink.receive (packet_dn);
							dnlen = packet_dn.getLength ();
							downlink_wr.write (packet_dn.getData (), 0, dnlen);
						} catch (SocketTimeoutException ste) {
							dnlen = 0;
						}
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
	
	public TunnelService (DatagramSocket uplink_socket, InetSocketAddress publicserver) {
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
		try {
			uplink_socket.setSoTimeout (1);
		} catch (SocketException se) {
			throw new RuntimeException ("UDP socket refuses turbo mode", se);
		}
		synchronized (this) {
			kas = new KeepAliveService (uplink_socket, publicserver);
			downlink_rd = new FileInputStream  (fio.getFileDescriptor ());
			downlink_wr = new FileOutputStream (fio.getFileDescriptor ());
			uplink = uplink_socket;
			tunserver = publicserver;
			notifyAll ();
			kas.start ();
		}
	}
	
	synchronized public void teardown () {
		try {
			synchronized (this) {
				if (kas != null) {
					kas.stop ();
					kas = null;
				}
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
			}
		} catch (IOException ioe) {
			;
		}
	}
	
	synchronized public void onRevoke () {
		this.teardown ();
	}
	
}
