package nl.openfortress.android6bed4;

import android.net.VpnService;
import android.os.ParcelFileDescriptor;

import java.net.*;
import java.io.*;


public final class TunnelService extends VpnService {

	static private ParcelFileDescriptor fio = null;
	static private FileInputStream  downlink_rd = null;
	static private FileOutputStream downlink_wr = null;

	static private InetSocketAddress tunserver = null;
	static private DatagramSocket uplink = null;

	static private boolean new_setup_defaultroute = true;
	static private boolean     setup_defaultroute = false;
	static byte new_local_address [] = new byte [16];
	static byte     local_address [] = null;
	
	static Worker worker;
	static Maintainer maintainer;
	
	//static final byte sender_unknown []        = {                   0,0,0,0, 0,0,0,0, 0,0,0,0, 0,0,0,0 };
	//static final byte linklocal_all_noders  [] = { (byte)0xff,(byte)0x02,0,0, 0,0,0,0, 0,0,0,0, 0,0,0,1 };
	//static final byte linklocal_all_routers [] = { (byte)0xff,(byte)0x02,0,0, 0,0,0,0, 0,0,0,0, 0,0,0,2 };
	
	final static byte IPPROTO_ICMPV6 = 58;
	
	final static byte ND_ROUTER_SOLICIT   = (byte) 133;
	final static byte ND_ROUTER_ADVERT    = (byte) 134;
	final static byte ND_NEIGHBOR_SOLICIT = (byte) 135;
	final static byte ND_NEIGHBOR_ADVERT  = (byte) 136;
	final static byte ND_REDIRECT         = (byte) 137;
	final static byte ND_LOWEST           = (byte) 133;
	final static byte ND_HIGHEST          = (byte) 137;
	
	final static byte ND_OPT_PREFIX_INFORMATION = 3;
	
	final static int OFS_IP6_SRC        = 8;
	final static int OFS_IP6_DST        = 24;
	final static int OFS_IP6_NXTHDR		= 6;
	final static int OFS_IP6_HOPS		= 7;
	
	final static int OFS_ICMP6_TYPE		= 40 + 0;
	final static int OFS_ICMP6_CODE		= 40 + 1;
	final static int OFS_ICMP6_CSUM		= 40 + 2;
	final static int OFS_ICMP6_DATA		= 40 + 4;
		
	final static byte router_solicitation [] = {
			// IPv6 header
			0x60, 0x00, 0x00, 0x00,
			16 / 256, 16 % 256, IPPROTO_ICMPV6, (byte) 255,
			0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,		 // unspecd src
			(byte) 0xff, 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x02, // all-rtr tgt
			// ICMPv6 header: router solicitation
			ND_ROUTER_SOLICIT, 0, 0x7a, (byte) 0xae,	// Checksum courtesy of WireShark :)
			// ICMPv6 body: reserved
			0, 0, 0, 0,
			// ICMPv6 option: source link layer address 0x0001 (end-aligned)
			0x01, 0x01, 0, 0, 0, 0, 0x00, 0x01,
		};

	final static byte router_linklocal_address [] = { (byte)0xfe,(byte)0x80,0,0, 0,0,0,0, 0,0,0,0, 0,0,0,0 };
	
	private class Worker extends Thread {

		/* The timing for polling of the two tunnel endpoints.
		 * There is an exponential fallback scheme, where the
		 * tunnel hopes to find the best frequency to watch
		 * for regularly occurring traffic.
		 */
		private int polling_millis     = 10;
		private int polling_millis_min =  1;
		private int polling_millis_max = 50;
		
		public void handle_4to6_nd (byte pkt [], int pktlen) {
			switch (pkt [OFS_ICMP6_TYPE]) {
			//
			// Handle Router Solicitation by dropping it -- this is a peer, not a router
			case ND_ROUTER_SOLICIT:
				return;
			//
			// Handle Router Advertisement as an addressing offering -- but validate the sender
			case ND_ROUTER_ADVERT:
				if (pktlen < 40 + 16 + 16) {
					// Too short to contain IPv6, ICMPv6 RtrAdv and Prefix Option
					return;
				}
				if ((pkt [OFS_ICMP6_DATA+1] & 0x80) != 0x00) {
					// Indecent proposal to use DHCPv6 over 6bed4
					return;
				}
				if (memdiff_addr (pkt, OFS_IP6_SRC, router_linklocal_address, 0)) {
					// Sender is not 0xfe80::/128
					return;
				}
				if (memdiff_halfaddr (pkt, OFS_IP6_DST, router_linklocal_address, 0)) {
					// Receiver address is not 0xfe80::/64
					return;
				}
				if ((pkt [OFS_IP6_DST + 11] != (byte) 0xff) || (pkt [OFS_IP6_DST + 12] != (byte) 0xfe)) {
					// No MAC-based destination address ending in ....:..ff:fe..:....
					return;
				}
				//TODO// Check if offered address looks like a multicast-address (MAC byte 0 is odd)
				//TODO// Check Secure ND on incoming Router Advertisement?
				//
				// Having validated the Router Advertisement, process its contents
				int destprefix_ofs = 0;
				int rdofs = OFS_ICMP6_DATA + 12;
				//TODO:+4_WRONG?// while (rdofs <= ntohs (v4v6plen) + 4) { ... }
				while (rdofs + 4 < pktlen) {
					if (pkt [rdofs + 1] == 0) {
						return;   /* zero length option */
					}
					if (pkt [rdofs + 0] != ND_OPT_PREFIX_INFORMATION) {
						/* skip to next option */
					} else if (pkt [rdofs + 1] != 4) {
						return;   /* bad length field */
					} else if (rdofs + (pkt [rdofs + 1] << 3) > pktlen + 4) {
						return;   /* out of packet length */
					} else if ((pkt [rdofs + 3] & (byte) 0xc0) != (byte) 0xc0) {
						/* no on-link autoconfig prefix */
					} else if (pkt [rdofs + 2] != 64) {
						return;
					} else {
						destprefix_ofs = rdofs + 16;
					}
					rdofs += (pkt [rdofs + 1] << 3);
				}
				if (destprefix_ofs > 0) {
					for (int i=0; i<8; i++) {
						new_local_address [0 + i] = pkt [destprefix_ofs + i];
						new_local_address [8 + i] = pkt [OFS_IP6_DST + 8 + i]; 
					}
					//TODO// syslog (LOG_INFO, "%s: Assigning address %s to tunnel\n", program, v6prefix);
					change_local_address ();  //TODO// parameters?
				}
				return;
			//
			// Neighbor Solicitation is an attempt to reach us peer-to-peer, and should be responded to
			case ND_NEIGHBOR_SOLICIT:
				//TODO// Respnd to Neighbor Solicitation
				return;
			//
			// Neighbor Advertisement may be in response to our peer-to-peer search
			case ND_NEIGHBOR_ADVERT:
				//TODO// Respond to Neighbor Advertisement
				return;
			// Route Redirect messages are not supported in 6bed4 draft v01
			case ND_REDIRECT:
				return;
			}
		}

		public void handle_4to6_plain (byte pkt [], int pktlen)
		throws IOException {
			downlink_wr.write (pkt, 0, pktlen);
		}
		
		public void handle_4to6 (DatagramPacket datagram)
		throws IOException {
			byte pkt [] = datagram.getData ();
			int pktlen = datagram.getLength ();

			if (pktlen < 40) {
				return;
			}
			if ((pkt [0] & (byte) 0xf0) != 0x60) {
				return;
			}
			validate_originator (pkt, (InetSocketAddress) datagram.getSocketAddress ());
			if ((pkt [OFS_IP6_NXTHDR] == IPPROTO_ICMPV6) && (pkt [OFS_ICMP6_TYPE] >= ND_LOWEST) && (pkt [OFS_ICMP6_TYPE] <= ND_HIGHEST)) {
				//
				// Not Plain: Router Adv/Sol, Neighbor Adv/Sol, Redirect
				handle_4to6_nd (pkt, pktlen);
			} else {
				//
				// Plain Unicast or Plain Multicast (both may enter)
				handle_4to6_plain (pkt, pktlen);
			}
		}

		private void memcp_address (byte tgt [], int tgtofs, byte src [], int srcofs) {
			for (int i=0; i<16; i++) {
				tgt [tgtofs+i] = src [srcofs+i];
			}
		}
		
		private boolean memdiff_addr (byte one[], int oneofs, byte oth[], int othofs) {
			for (int i=0; i<16; i++) {
				if (one [oneofs + i] != oth [othofs + i]) {
					return true;
				}
			}
			return false;
		}
		
		private boolean memdiff_halfaddr (byte one[], int oneofs, byte oth[], int othofs) {
			for (int i=0; i<8; i++) {
				if (one [oneofs + i] != oth [othofs + i]) {
					return true;
				}
			}
			return false;
		}
		
		public void validate_originator (byte pkt [], InetSocketAddress originator)
		throws IOException {
/* TODO: validate originator address
			if (tunserver.equals (originator)) {
				return;
			}
			if (memdiff_halfaddr (pkt, OFS_IP6_SRC, router_linklocal, 0) && ((local_address == null) || memdiff_halfaddr (pkt, OFS_IP6_SRC, local_address, 8))) {
				throw new IOException ("Incoming 6bed4 uses bad /64 prefix");
			}
			int port = (pkt [OFS_IP6_SRC + 8] ^ 0x02) & 0xff;
			port = (port | (pkt [OFS_IP6_SRC + 9] << 8) & 0xffff;
			if (originator.getPort () != port) {
				throw new IOException ("Incoming 6bed4 uses ")
			}
*/
		}

		public void handle_6to4_plain_unicast (byte pkt [], int pktlen)
		throws IOException {
			uplink.send (new DatagramPacket (pkt, pktlen, tunserver));
		}
		
		public void handle_6to4_nd (byte pkt [], int pktlen)
		throws IOException {
			switch (pkt [OFS_ICMP6_TYPE]) {
			//
			// Handle Router Solicitation by answering it with the local configuration
			case ND_ROUTER_SOLICIT:
				int ofs = OFS_ICMP6_TYPE;
				pkt [ofs++] = ND_ROUTER_ADVERT;		// type
				pkt [ofs++] = 0;					// code
				ofs += 2;							// checksum
				pkt [ofs++] = 0;					// hop limit -- unspecified
				pkt [ofs++] = 0x18;					// M=0, O=0, H=0, Prf=11=Low, Reserved=0
				pkt [ofs++] = setup_defaultroute? (byte) 0xff: 0x00;	// Lifetime
				pkt [ofs++] = setup_defaultroute? (byte) 0xff: 0x00;	// (cont)
				for (int i=0; i<8; i++) {
					pkt [ofs++] = 0;				// Reachable time, Retrans timer
				}
				pkt [ofs-6] = (byte) 0x80;			// Reachable time := 32s
				pkt [ofs-2] = 0x01;					// Retrans timer := 0.25s
				// Start of Prefix Option
				pkt [ofs++] = ND_OPT_PREFIX_INFORMATION;
				pkt [ofs++] = 4;		// Option length = 4 * 8 bytes
				pkt [ofs++] = (byte) 128;		// Announce a /64 prefix (TODO: Temporarily /128)
				pkt [ofs++] = (byte) 0x80;	// Link-local, No autoconfig, tunnel does the work
				for (int i=0; i<8; i++) {
					pkt [ofs++] = (byte) 0xff;		// Valid / Preferred Lifetime: Infinite
				}
				for (int i=0; i<4; i++) {
					pkt [ofs++] = 0;				// Reserved
				}
				memcp_address (pkt, ofs, local_address, 0);
				ofs += 16;
				// End of Prefix Option
				memcp_address (pkt, OFS_IP6_DST, pkt, OFS_IP6_SRC);	// dst:=src
				memcp_address (pkt, OFS_IP6_SRC, local_address, 0);
				//TODO// Send packet back to IPv6 downlink
				return;
			//
			// Handle Router Advertisement by dropping it -- Android is not setup a router
			case ND_ROUTER_ADVERT:
				return;
			//
			// Neighbor Solicitation is an attempt to reach a peer directly
			case ND_NEIGHBOR_SOLICIT:
				//TODO// Handle Neighbor Solicitation
				return;
			//
			// Neighbor Advertisement is a response to a peer, and should be relayed
			case ND_NEIGHBOR_ADVERT:
				//TODO// Possibly arrange the peer's receiving address 
				handle_6to4_plain_unicast (pkt, pktlen);
				return;
			// Route Redirect messages are not supported in 6bed4 draft v01
			case ND_REDIRECT:
				return;
			}
		}

		public void handle_6to4 (byte pkt [], int pktlen)
		throws IOException {
			if (pktlen < 41) {
				return;
			}
			if ((pkt [0] & 0xf0) != 0x60) {
				return;
			}
			if ((pkt [OFS_IP6_NXTHDR] == IPPROTO_ICMPV6) && (pkt [OFS_ICMP6_TYPE] >= ND_LOWEST) && (pkt [OFS_ICMP6_TYPE] <= ND_HIGHEST)) {
				//
				// Not Plain: Router Adv/Sol, Neighbor Adv/Sol, Redirect
				handle_6to4_nd (pkt, pktlen);
			} else if ((pkt [OFS_IP6_DST+0] != 0xff) && ((pkt [OFS_IP6_DST+8] & 0x01) == 0x00)) {
				//
				// Plain Unicast
				pkt [OFS_IP6_HOPS]--;
				if (pkt [OFS_IP6_HOPS] == 0) {
					return;
				}
				handle_6to4_plain_unicast (pkt, pktlen);
			} else {
				//
				// Plain Multicast
				//TODO: Ignore Multicast for now...
			}
		}

		/* The worker thread pulls data from both tunnel ends, and passes it on to the other.
		 * Packets dealing with Neighbor Discovery are treated specially by this thread. 
		 */
		public void run () {
			//
			// Pump data back and forth, interpreting Neighbor Discovery locally
			byte packet_up [] = new byte [1280];
			DatagramPacket packet_dn = new DatagramPacket (new byte [1280+28], 1280+28);
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
				int uplen = 0;
				boolean nothingdown;
				synchronized (this) {
					try {
						if (downlink_rd != null) {
							uplen = downlink_rd.read (packet_up);
						}
						if (uplen > 0) {
							handle_6to4 (packet_up, uplen);
						}
					} catch (SocketTimeoutException ex) {
						;
					} catch (IOException ex) {
						;
					} catch (ArrayIndexOutOfBoundsException ex) {
						;
					}
				}
				synchronized (this) {
					if (uplink == null) {
						nothingdown = true;
					} else {
						try {
							uplink.receive (packet_dn);
							handle_4to6 (packet_dn);
							nothingdown = false;
						} catch (SocketTimeoutException ex) {
							nothingdown = true;
						} catch (IOException ex) {
							nothingdown = true;
						} catch (ArrayIndexOutOfBoundsException ex) {
							nothingdown = true;
						}
					}
				}
				if ((uplen == 0) || nothingdown) {
					try {
						polling_millis <<= 1;
						if (polling_millis > polling_millis_max) {
							polling_millis = polling_millis_max;
						}
						sleep (polling_millis);
					} catch (InterruptedException ie) {
						;	// Great, let's move on!
					}
				} else {
					polling_millis = polling_millis_min;
				}
			}
		}
	}
	
	private class Maintainer extends Thread {
		
		/* The time for the next scheduled maintenance: routersol or keepalive.
		 * The milliseconds are always 0 for maintenance tasks.
		 */
		private long maintenance_time_millis;
		private int maintenance_time_cycle = 0;
		private int maintenance_time_cycle_max = 30;
		private boolean have_lladdr = false;
		private int keepalive_period = 30;
		private int keepalive_ttl = -1;
		private DatagramPacket keepalive_packet = null;
	
		/* Perform the initial Router Solicitation exchange with the public server.
		 */
		public void solicit_router () {
			if (uplink != null) {
				try {
					DatagramPacket rtrsol = new DatagramPacket (router_solicitation, router_solicitation.length, tunserver);
					uplink.send (rtrsol);
				} catch (IOException ioe) {
					throw new RuntimeException ("Network failure", ioe);
				}
			}
		}
		
		/* Send a KeepAlive packet to the public server.
		 * Note, ideally, we would set a low-enough TTL to never reach it;
		 * after all, the only goal is to open /local/ firewalls and NAT.
		 * Java however, is not capable of setting TTL on unicast sockets.
		 */
		public void keepalive () {
			if ((keepalive_packet != null) && (uplink != null)) {
				try {
					uplink.send (keepalive_packet);
				} catch (IOException ioe) {
					;	/* Better luck next time? */
				}
			}
		}
		
		/* Perform regular maintenance tasks: KeepAlive, and requesting a local address.
		 */
		public void regular_maintenance () {
			if (!have_lladdr) {
				solicit_router ();
				maintenance_time_cycle <<= 1;
				maintenance_time_cycle += 1;
				if (maintenance_time_cycle > maintenance_time_cycle_max) {
					maintenance_time_cycle = maintenance_time_cycle_max;
				}
				//TODO// syslog (LOG_INFO, "Sent Router Advertisement to Public 6bed4 Service, next attempt in %d seconds\n", maintenance_time_cycle);
			} else {
				//TODO// syslog (LOG_INFO, "Sending a KeepAlive message (empty UDP) to the 6bed4 Router\n");
				keepalive ();
				maintenance_time_cycle = maintenance_time_cycle_max;
			}
			maintenance_time_millis = System.currentTimeMillis () + 1000 * (long) maintenance_time_cycle;
		}
		
		/* Tell the maintenance routine whether a local address has been setup.
		 * Until this is called, the maintenance will focus on getting one through
		 * regular Router Solicitation messages.  It is possible to revert to this
		 * behaviour by setting the flag to false; this can be useful in case of
		 * changes, for instance resulting from an IPv4 address change.
		 */
		public void have_local_address (boolean new_setting) {
			have_lladdr = new_setting;
			if (have_lladdr) {
				maintenance_time_cycle = maintenance_time_cycle_max;
				maintenance_time_millis = System.currentTimeMillis () + 1000 * maintenance_time_cycle;
			}
		}
		
		/* Run the regular maintenance thread.  This involves sending KeepAlives
		 * and possibly requesting a local address through Router Solicitation.
		 */
		public void run () {
			try {
				while (true) {
					regular_maintenance ();
					sleep (maintenance_time_millis - System.currentTimeMillis());
				}
			} catch (InterruptedException ie) {
				;
			}
		}
		
		/* Construct the Maintainer thread.
		 */
		public Maintainer (SocketAddress server) {
			byte payload [] = { };
			if (server != null) {
				try {
					keepalive_packet = new DatagramPacket (payload, 0, server);
				} catch (SocketException se) {
					keepalive_packet = null;
				}
			}
		}
	}
	
	public TunnelService () {
	}
	
	/* Notify the tunnel of a new local address.  This is called after
	 * updating the values of the following variables which are
	 * private, but accessible to the maintenance cycle:
	 *  - byte new_local_address [16]
	 *  - boolean new_setup_defaultroute
	 */
	public void change_local_address () {
		Builder builder;
		builder = new Builder ();
		builder.setSession ("6bed4 uplink to IPv6");
		builder.setMtu (1280);
		try {
			//TODO// For now, setup a /128 address to avoid v01-style Neighbor Discovery
			builder.addAddress (Inet6Address.getByAddress (new_local_address), 128);
		} catch (UnknownHostException uhe) {
			throw new RuntimeException ("6bed4 address rejected", uhe);
		}
		if (new_setup_defaultroute) {
			builder.addRoute ("::", 0);
		}
		if (fio != null) {
			try {
				fio.close ();
			} catch (IOException ioe) {
				; /* Uncommon: Fallback to garbage collection */
			}
		}
		setup_defaultroute = new_setup_defaultroute;
		local_address = new_local_address;
		fio = builder.establish ();
		try {
			uplink.setSoTimeout (1);
		} catch (SocketException se) {
			throw new RuntimeException ("UDP socket refuses turbo mode", se);
		}
		synchronized (this) {
			downlink_rd = new FileInputStream  (fio.getFileDescriptor ());
			downlink_wr = new FileOutputStream (fio.getFileDescriptor ());
		}
		synchronized (worker) {
			worker.notifyAll ();
		}
		maintainer.have_local_address (true);
	}
		
	public TunnelService (DatagramSocket uplink_socket, InetSocketAddress publicserver) {
		synchronized (this) {
			uplink = uplink_socket;
			tunserver = publicserver;
			// notifyAll ();
		}
		//
		// Create the worker thread that will pass information back and forth
		if (worker == null) {
			worker = new Worker ();
			worker.start ();
		} else {
			worker.notifyAll ();
		}
		if (maintainer == null) {
			maintainer = new Maintainer (tunserver);
			maintainer.start ();
		} else {
			maintainer.have_local_address (false);
		}
	}
	
	synchronized public void teardown () {
		try {
			synchronized (this) {
				if (worker != null) {
					worker.interrupt ();
					worker = null;
				}
				if (maintainer != null) {
					maintainer.interrupt ();
					maintainer = null;
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
			;	/* Uncommon: Fallback to garbage collection */
		}
	}
	
	synchronized public void onRevoke () {
		this.teardown ();
	}
	
}
