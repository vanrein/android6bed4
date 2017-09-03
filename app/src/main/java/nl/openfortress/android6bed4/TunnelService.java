package nl.openfortress.android6bed4;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.content.SharedPreferences;

import java.net.*;
import java.io.*;


public final class TunnelService
extends VpnService
{

	private static final String TAG = "TunnelService";
	public static final String BROADCAST_VPN_STATE = "nl.openfortress.android6bed4.VPN_STATE";

	static private TunnelService singular_instance = null;
	
	static private ParcelFileDescriptor fio = null;
	static private FileInputStream  downlink_rd = null;
	static private FileOutputStream downlink_wr = null;

	static private InetSocketAddress  tunserver = null;
	static private DatagramSocket     uplink = null;

	static private boolean     setup_defaultroute = true;
	static byte     local_address [] = new byte [16];

	static private NeighborCache ngbcache = null;
	
	static public SharedPreferences prefs;
	
	static Worker worker;
	static Maintainer maintainer;
	
	//static final byte sender_unknown []        = {                   0,0,0,0, 0,0,0,0, 0,0,0,0, 0,0,0,0 };
	//static final byte linklocal_all_noders  [] = { (byte)0xff,(byte)0x02,0,0, 0,0,0,0, 0,0,0,0, 0,0,0,1 };
	//static final byte linklocal_all_routers [] = { (byte)0xff,(byte)0x02,0,0, 0,0,0,0, 0,0,0,0, 0,0,0,2 };
	
	final static byte IPPROTO_ICMPV6 = 58;
	final static byte IPPROTO_TCP = 6;
	
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
	final static int OFS_IP6_PLEN       = 4;
	final static int OFS_IP6_NXTHDR		= 6;
	final static int OFS_IP6_HOPS		= 7;
	
	final static int OFS_ICMP6_TYPE		= 40 + 0;
	final static int OFS_ICMP6_CODE		= 40 + 1;
	final static int OFS_ICMP6_CSUM		= 40 + 2;
	final static int OFS_ICMP6_DATA		= 40 + 4;
	
	final static int OFS_ICMP6_NGBSOL_TARGET = 40 + 8;
	final static int OFS_ICMP6_NGBADV_TARGET = 40 + 8;
	final static int OFS_ICMP6_NGBADV_FLAGS  = 40 + 4;
		
	final static int OFS_TCP6_FLAGS	    = 13;
	final static int TCP_FLAG_SYN		= 0x02;
	final static int TCP_FLAG_ACK		= 0x01;
	
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
		
		public void handle_4to6_nd (byte pkt [], int pktlen, SocketAddress src)
		throws IOException, SocketException {
			if (checksum_icmpv6 (pkt, 0) != fetch_net16 (pkt, OFS_ICMP6_CSUM)) {
				// Checksum is off
				return;
			}
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
						local_address [0 + i] = pkt [destprefix_ofs + i];
						local_address [8 + i] = pkt [OFS_IP6_DST + 8 + i];
					}
					//TODO// syslog (LOG_INFO, "%s: Assigning address %s to tunnel\n", program, v6prefix);
					update_local_netconfig ();
					Log.i (TAG, "Assigned address to tunnel");
				}
				return;
			//
			// Neighbor Solicitation is an attempt to reach us peer-to-peer, and should be responded to
			case ND_NEIGHBOR_SOLICIT:
				if (pktlen < 24) {
					// Too short to make sense
					return;
				}
				if (memdiff_addr (pkt, OFS_ICMP6_NGBSOL_TARGET, local_address, 0)) {
					// Neighbor Solicitation not aimed at me
					return;
				}
				if (!ngbcache.is6bed4 (pkt, OFS_IP6_SRC)) {
					// Source is not a 6bed4 address
					return;
				}
				//
				// Not checked here: IPv4/UDP source versus IPv6 source address (already done)
				// Not checked here: LLaddr in NgbSol -- simply send back to IPv6 src address
				//
				memcp_address (pkt, OFS_IP6_DST, pkt, OFS_IP6_SRC);
				memcp_address (pkt, OFS_IP6_SRC, local_address, 0);
				pkt [OFS_ICMP6_TYPE] = ND_NEIGHBOR_ADVERT;
				pkt [OFS_IP6_PLEN + 0] = 0;
				pkt [OFS_IP6_PLEN + 1] = 8 + 16;
				pkt [OFS_ICMP6_NGBADV_FLAGS] = 0x60;	// Solicited, Override
				// Assume that OFS_ICMP6_NGBADV_TARGET == OFS_ICMP6_NGBSOL_TARGET
				int csum = TunnelService.checksum_icmpv6 (pkt, 0);
				pkt [OFS_ICMP6_CSUM + 0] = (byte) (csum >> 8  );
				pkt [OFS_ICMP6_CSUM + 1] = (byte) (csum & 0xff);
				DatagramPacket replypkt = new DatagramPacket (pkt, 0, 40 + 8 + 16, src);
				uplink.send (replypkt);

				//
				// TODO:OLD Replicate the message over the tunnel link
				//
				// We should attach a Source Link-Layer Address, but
				// we cannot automatically trust the one provided remotely.
				// Also, we want to detect if routes differ, and handle it.
				//
				// 0. if no entry in the ngb.cache
				//    then use 6bed4 server in ND, initiate ngb.sol to src.ll
				//         impl: use 6bed4-server lladdr, set highest metric
				// 1. if metric (ngb.cache) < metric (src.ll)
				//    then retain ngb.cache, send Redirect to source
				// 2. if metric (ngb.cache) > metric (src.ll)
				//    then retain ngb.cache, initiate ngb.sol to src.ll
				// 3. if metric (ngb.cache) == metric (src.ll)
				//    then retain ngb.cache
				//
				//TODO// Handle ND_NEIGHBOR_SOLICIT (handle_4to6_nd)
				return;
			//
			// Neighbor Advertisement may be in response to our peer-to-peer search
			case ND_NEIGHBOR_ADVERT:
                //
                // Process Neighbor Advertisement coming in over 6bed4
                // First, make sure it is against an item in the ndqueue
				//
				// Validate the Neighbor Advertisement
				if (pktlen < 64) {
					// Packet too small to hold ICMPv6 Neighbor Advertisement
					return;
				}
				if ((pkt [OFS_ICMP6_TYPE] != ND_NEIGHBOR_ADVERT) || (pkt [OFS_ICMP6_CODE] != 0)) {
					// ICMPv6 Type or Code is wrong
					return;
				}
				if ((!ngbcache.is6bed4 (pkt, OFS_IP6_SRC)) || (!ngbcache.is6bed4 (pkt, OFS_IP6_DST))) {
					// Source or Destination IPv6 address is not a 6bed4 address
					return;
				}
				if (memdiff_addr (pkt, OFS_IP6_SRC, pkt, OFS_ICMP6_NGBADV_TARGET)) {
					// NgbAdv's Target Address does not match IPv6 source
					return;
				}
				//
				// Not checked here: IPv4/UDP source versus IPv6 source address (already done)
				//
				ngbcache.received_peer_direct_acknowledgement (pkt, OFS_ICMP6_NGBADV_TARGET, false);
				return;
			//
			// Route Redirect messages are not supported in 6bed4 draft v01
			case ND_REDIRECT:
				return;
			}
		}

		/* Forward an IPv6 packet, wrapped into UDP and IPv4 in the 6bed4
		 * way, as a pure IPv6 packet over the tunnel interface.  This is
		 * normally a simple copying operation.  One exception exists for
		 * TCP ACK packets; these may be in response to a "playful" TCP SYN
		 * packet that was sent directly to the IPv4 recipient.  This is a
		 * piggyback ride of the opportunistic connection efforts on the
		 * 3-way handshake for TCP, without a need to modify the packets!
		 * The only thing needed to make that work is to report success
		 * back to the Neighbor Cache, in cases when TCP ACK comes back in
		 * directly from the remote peer.
		 * 
		 * Note that nothing is stopping an ACK packet that is meaningful
		 * to us from also being a SYN packet that is meaningful to the
		 * remote peer.  We will simply do our thing and forward any ACK
		 * to the most direct route we can imagine -- which may well be
		 * the sender, _especially_ since we opened our 6bed4 port to the
		 * remote peer when sending our playful initial TCP packet.
		 * 
		 * Observing the traffic on the network, this may well look like
		 * magic!  All you see is plain TCP traffic crossing over directly
		 * if it is possible --and bouncing one or two packets through the
		 * tunnel otherwise-- and especially in the case where it can work
		 * directly it will be a surprise.  Servers are therefore strongly
		 * encouraged to setup port forwarding for their 6bed4 addresses,
		 * or just open a hole in full cone NAT/firewall setups.  This will
		 * mean zero delay and zero bypasses for 6bed4 on the majority of
		 * TCP connection initiations between 6bed4 peers!
		 */
		public void handle_4to6_plain (byte pkt [], int pktlen)
		throws IOException {
			if (downlink_wr != null) {
				downlink_wr.write (pkt, 0, pktlen);
			}
			//
			// If this is a successful peering attempt, that is, a tcpack packet, report that back
			// Note that the UDP/IPv4 source has already been validated against the IPv6 source
			boolean tcpack = (pktlen >= 40 + 20) && (pkt [OFS_IP6_NXTHDR] == IPPROTO_TCP) && ((pkt [OFS_TCP6_FLAGS] & TCP_FLAG_ACK) != 0x00);
			if (tcpack) {
				if (ngbcache.is6bed4 (pkt, OFS_IP6_SRC)) {
					ngbcache.received_peer_direct_acknowledgement (pkt, OFS_IP6_SRC, true);
				}
			}
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
				handle_4to6_nd (pkt, pktlen, datagram.getSocketAddress ());
			} else {
				//
				// Plain Unicast or Plain Multicast (both may enter)
				handle_4to6_plain (pkt, pktlen);
			}
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

		/* This routine passes IPv6 traffic from the tunnel interface on
		 * to the 6bed4 interface where it is wrapped into UDP and IPv4.
		 * The only concern for this is where to send it to -- should it
		 * be sent to the tunnel server, or directly to the peer?  The
		 * Neighbor Cache is consulted for advise.
		 * 
		 * A special flag exists to modify the behaviour of the response
		 * to this inquiry.  This flag is used to signal that a first
		 * packet might be tried directly, which should be harmless if
		 * it fails and otherwise lead to optimistic connections if:
		 *  1. the packet will repeat upon failure, and
		 *  2. explicit acknowledgement can be reported to the cache
		 * This is the case with TCP connection setup; during a SYN,
		 * it is possible to be playful and try to send the first
		 * packet directly.  A TCP ACK that returns directly from the
		 * sender indicates that return traffic is possible, which is
		 * then used to update the Neighbor Cache with positivism on
		 * the return route.
		 */
		public void handle_6to4_plain_unicast (byte pkt [], int pktlen)
		throws IOException {
			InetSocketAddress target;
			if ((ngbcache != null) && ngbcache.is6bed4 (pkt, 24)) {
				boolean tcpsyn = (pkt [OFS_IP6_NXTHDR] == IPPROTO_TCP) && ((pkt [OFS_TCP6_FLAGS] & TCP_FLAG_SYN) != 0x00);
				target = ngbcache.lookup_neighbor (pkt, 24, tcpsyn);
			} else {
				target = tunserver;
			}
			uplink.send (new DatagramPacket (pkt, pktlen, target));
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
			// Neighbor Solicitation is not normally sent by the phone due to its /128 on 6bed4
			case ND_NEIGHBOR_SOLICIT:
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
			while (!isInterrupted ()) {
				int uplen = 0;
				boolean nothingdown;
				synchronized (this) {
					try {
						if (downlink_rd != null) {
							uplen = downlink_rd.read (packet_up);
						}
						if (uplen > 0) {
							Log.d(TAG, "uplen = " + Integer.toString(uplen));
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
							Log.d(TAG, packet_dn.getAddress().toString());
							Log.d(TAG, Integer.toString(packet_dn.getLength()));
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
					polling_millis <<= 1;
					if (polling_millis > polling_millis_max) {
						polling_millis = polling_millis_max;
					}
					try {
						sleep (polling_millis);
					} catch (InterruptedException ie) {
						return;
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
					/* Network is probably down, so don't
					 * throw new RuntimeException ("Network failure", ioe);
					 */
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
					Log.i (TAG, "Sent KeepAlive (empty UDP) to Tunnel Server");
				} catch (IOException ioe) {
					;	/* Network is probably down; order reconnect to tunnel server */
					have_lladdr = false;
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
				Log.i (TAG, "Sent Router Advertisement to Tunnel Server");
			} else {
				//TODO// syslog (LOG_INFO, "Sending a KeepAlive message (empty UDP) to the 6bed4 Router\n");
				keepalive ();
				if (have_lladdr) {
					maintenance_time_cycle = maintenance_time_cycle_max;
				} else {
					maintenance_time_cycle = 1;
				}
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
		
		/* See if a local address has been setup.
		 */
		public boolean have_local_address () {
			return have_lladdr;
		}

		/* Run the regular maintenance thread.  This involves sending KeepAlives
		 * and possibly requesting a local address through Router Solicitation.
		 */
		public void run () {
			try {
				while (!isInterrupted ()) {
					regular_maintenance ();
					sleep (maintenance_time_millis - System.currentTimeMillis());
				}
			} catch (InterruptedException ie) {
				return;
			}
		}
		
		/* Change the tunnel server addressed by the Maintainer thread
		 */
		public void change_tunnel_server (SocketAddress server) {
			byte payload [] = { };
			if (server != null) {
				try {
					keepalive_packet = new DatagramPacket (payload, 0, server);
				} catch (SocketException se) {
					keepalive_packet = null;
				}
			}
			have_local_address (false);
		}
		
		/* Construct the Maintainer thread.
		 */
		public Maintainer () {
			;
		}
	}
	
	/* Process changes in the preferences, as they can be made asynchronously in the Android6bed4 service.
	 * 
	 * Approach: Assume that preferences may have been set, or that this is the first running time.  The
	 * Android6bed4 Activity will ensure invoking this routine once, when switching to an enabled tunnel
	 * service, even at boot time.  At this time, all settings of use to the TunnelService are certain
	 * to have been initialised -- so no assumptions about default values have to be made.
	 * 
	 * This routine ignores the key handed over, and will simply go over the offered values and setup
	 * the tunnel.  Any existing values are retained if possible; for instance, if the UDP port is already
	 * bound, then its handle will be recycled if the port number is the same (or the new port permits any
	 * port number because it is unset/random).
	 * 
	 * TODO: It would be nice if the breakdown of the TunnelService would be reported back to the
	 * Activity.  Perhaps in a future version; for now the Activity will have to rely on theTunnelService()
	 * to find if a tunnel is active.  And of course the key symbol indicates Android's take on tunnel life.
	 */
	public void applyPreferences() {
		//
		// See if the default route must be set through 6bed4
		setup_defaultroute = prefs.getBoolean ("overtake_default_route", true);
		//
		// Find the tunnel server IP.  Note that a change in this address
		// does not imply a change in the local endpoint -- they are managed
		// orthogonally, so switching back and forth between tunnel servers
		// without change to the client IP address means that an old 6bed4
		// address can be reused.
		try {
			tunserver = new InetSocketAddress (Inet4Address.getByName (prefs.getString ("tunserver_ip", "")), 25788);
		} catch (UnknownHostException uhe) {
			throw new RuntimeException ("Failed to address tunnel server", uhe);
		}
		start ();
		maintainer.change_tunnel_server (tunserver);
		//
		// Find the UDP port to use on the tunnel client's IPv4 endpoint
		// Share the old port handle if available and if set
		int prefport = prefs.getInt ("tunclient_port", 0);
		try {
			if (prefport > 0) {
				uplink = new DatagramSocket (prefport);
			} else {
				uplink = new DatagramSocket (); // Fallback is random port
			}
			uplink.setSoTimeout (1);
		} catch (IOException ioe) {
			throw new RuntimeException ("Failure to bind to random UDP port", ioe);
		}
		if ((maintainer != null) && maintainer.have_local_address ()) {
			update_local_netconfig ();
		} else {
			start ();
		}
	}
	
	/* Notify the tunnel of a new local address.  This is called after
	 * updating the values of the following variables which are
	 * private, but accessible to the maintenance cycle:
	 *  - byte new_local_address [16]
	 *  - boolean new_setup_defaultroute
	 */
	public void update_local_netconfig () {
		Builder builder;
		builder = new Builder ();
		builder.setSession ("6bed4 uplink to IPv6");
		builder.setMtu (1280);
		try {
			builder.addAddress (Inet6Address.getByAddress (local_address), 64);
			builder.addDnsServer("8.8.8.8");
		} catch (UnknownHostException uhe) {
			throw new RuntimeException ("6bed4 address rejected", uhe);
		}
		if (setup_defaultroute) {
			Log.i (TAG, "Creating default route through 6bed4 tunnel");
			builder.addRoute ("::", 0);
		} else {
			Log.i (TAG, "Skipping default route through 6bed4 tunnel");
		}
		if (fio != null) {
			try {
				fio.close ();
			} catch (IOException ioe) {
				; /* Uncommon: Fallback to garbage collection */
			}
		}
		//
		//
		// Setup a new neighboring cache, possibly replacing an old one
		if (ngbcache != null) {
			ngbcache.cleanup ();
		}
		ngbcache = new NeighborCache (uplink, tunserver, local_address);
		//
		// Now actually construct the tunnel as prepared
		fio = builder.establish ();
		synchronized (this) {
			downlink_rd = new FileInputStream  (fio.getFileDescriptor ());
			downlink_wr = new FileOutputStream (fio.getFileDescriptor ());
		}
		synchronized (worker) {
			worker.notifyAll ();
		}
		maintainer.have_local_address (true);
	}
	
	/* TODO: Create automatic processing of Android IPv6 address lists
	public void notify_ipv6_addresses (Collection <byte []> addresslist) {
		// See if the IPv6 address list causes a change to the wish for a default route through 6bed4
		Iterator <byte []> adr_iter = addresslist.iterator();
		new_setup_defaultroute = true;
		while (adr_iter.hasNext ()) {
			byte addr [] = adr_iter.next ();
			if ((addr.length == 16) && memdiff_addr (addr, 0, local_address, 0)) {
				new_setup_defaultroute = false;
			}
		}
		// If the default route should change, change the local network configuration
		if (new_setup_defaultroute != setup_defaultroute) {
			update_local_netconfig ();
		}
	}
	*/
	@Override
	public void onCreate() {
		super.onCreate();
		applyPreferences();
		LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(BROADCAST_VPN_STATE).putExtra("running", true));
	}

	/* Start active service (if not already running) */
	public synchronized void start () {
		//
		// Create the worker thread that will pass information back and forth
		if (worker == null) {
			worker = new Worker ();
			worker.start ();
		} else {
			synchronized (worker) {
				worker.notifyAll ();
			}
		}
		if (maintainer == null) {
			maintainer = new Maintainer ();
			maintainer.start ();
		} else {
			maintainer.have_local_address (false);
		}
	}
	
	/* Stop active service (if running) */
	public synchronized void stop () {
		try {
			if (ngbcache != null) {
				ngbcache.cleanup ();
			}
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
			ngbcache = null;
		} catch (IOException ioe) {
			;	/* Uncommon: Fallback to garbage collection */
		}
	}
	
	public TunnelService () {
		//
		// Setup a link to the instance of this singular class, for use in the EventMonitor
		singular_instance = this;
	}

	/* TODO: REMOVE OLD, STATICALLY PARAMETERISED CONSTRUCTOR:
	public TunnelService (DatagramSocket uplink_socket, InetSocketAddress publicserver) {
		synchronized (this) {
			uplink = uplink_socket;  //TODO// timeout!
			tunserver = publicserver;
			// notifyAll ();
		}
		//
		// Setup a link to the instance of this singular class, for use in the EventMonitor
		singular_instance = this;
		//
		// Create the worker thread that will pass information back and forth
		if (worker == null) {
			worker = new Worker ();
			worker.start ();
		} else {
			synchronized (worker) {
				worker.notifyAll ();
			}
		}
		if (maintainer == null) {
			maintainer = new Maintainer ();
			maintainer.change_tunnel_server (tunserver);
			maintainer.start ();
		} else {
			maintainer.have_local_address (false);
		}
	}
	*/
	
	public static TunnelService theTunnelService () {
		return singular_instance;
	}
	
	public static boolean isRunning () {
		if (singular_instance == null) {
			return false;
		}
		if (singular_instance.worker == null) {
			return false;
		}
		if (singular_instance.maintainer == null) {
			return false;
		}
		return true;
	}

	@Override
	public void onDestroy()
	{
		singular_instance = null;
		stop ();
		if (uplink != null) {
			uplink.close ();
			uplink = null;
		}
		Log.i(TAG, "Stopped");
	}

	/***
	 *** UTILITY FUNCTIONS
	 ***/

	public static void memcp_address (byte tgt [], int tgtofs, byte src [], int srcofs) {
		for (int i=0; i<16; i++) {
			tgt [tgtofs+i] = src [srcofs+i];
		}
	}
	
	public static boolean memdiff_addr (byte one[], int oneofs, byte oth[], int othofs) {
		for (int i=0; i<16; i++) {
			if (one [oneofs + i] != oth [othofs + i]) {
				return true;
			}
		}
		return false;
	}
	
	public static boolean memdiff_halfaddr (byte one[], int oneofs, byte oth[], int othofs) {
		for (int i=0; i<8; i++) {
			if (one [oneofs + i] != oth [othofs + i]) {
				return true;
			}
		}
		return false;
	}

	/* Retrieve an unsigned 16-bit value from a given index in a byte array and
	 * return it as an integer.
	 */
	public static int fetch_net16 (byte pkt [], int ofs16) {
		int retval = ((int) pkt [ofs16]) << 8 & 0xff00;
		retval = retval + (((int) pkt [ofs16+1]) & 0xff);
		return retval;
	}
	
	/* Fill in the ICMPv6 checksum field in a given IPv6 packet.
	 */
	public static int checksum_icmpv6 (byte pkt [], int pktofs) {
		int plen = fetch_net16 (pkt, pktofs + OFS_IP6_PLEN);
		int nxth = ((int) pkt [pktofs + 6]) & 0xff;
		// Pseudo header is IPv6 src/dst (included with packet) and plen/nxth and zeroes:
		int csum = plen + nxth;
		int i;
		for (i=8; i < 40+plen; i += 2) {
			if (i != OFS_ICMP6_CSUM) {
				// Skip current checksum value
				csum += fetch_net16 (pkt, pktofs + i);
			}
		}
		// No need to treat a trailing single byte: ICMPv6 has no odd packet lengths
		csum = (csum & 0xffff) + (csum >> 16);
		csum = (csum & 0xffff) + (csum >> 16);
		csum = csum ^ 0xffff;	// 1's complement limited to 16 bits
		return csum;
	}
	
}
