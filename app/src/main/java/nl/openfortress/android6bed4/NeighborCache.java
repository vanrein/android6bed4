package nl.openfortress.android6bed4;


import java.util.Hashtable;
import java.util.Queue;
import java.util.ArrayDeque;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Inet4Address;
import java.net.UnknownHostException;

import java.io.IOException;



/* The Neighbor Cache is modelled in Java, because Android/Java won't
 * give access to the network stack.
 * 
 * The primary task of the cache is to map IPv6-addresses in byte [16]
 * format to a destination, which is stored as a Inet4SocketAddress
 * for direct use with DatagramPacket facilities in Java.  A hash is
 * used to gain fast access to the target object.
 * 
 * To find mappings, the cache will send up to 3 Neighor Solicitation
 * messages to the direct peer.  During this period it will pass through
 * states ATTEMPT1 to ATTEMPT3, and finally become FAILED.  This is a sign
 * that no further attempts need to be made until the expiration of the
 * mapping, about 30 seconds after starting it.  This process starts
 * when an unknown peer is approached, or one that is STALE.
 * 
 * If a Neighbor Advertisement comes in, this information is sent here
 * for processing.  The cache entry will usually go to REACHABLE in such
 * situations.
 * 
 * When traffic comes in with the direct address of this peer as its
 * destination, then the cache is informed; if no mapping in REACHABLE
 * state exists, then new attempts are started, so as to enable direct
 * connectivity after the remote peer has opened a hole to us.
 * 
 * The cache maintains a seperate timer queue for each state, and will
 * act upon the objects if they expire.  This makes the queue rather
 * self-controlled.
 * 
 * Before changing any piece of this code, please assure yourself that
 * you understand each single synchronized section, and convince
 * yourself that you understand the trick in NeighborQueue.dequeue().
 * This is not just multitasking code; it may well be the most complex
 * example of multitasking code that you ever came accross.  Do not
 * change the code unless you are absolutely certain that you have
 * understood all the invariants that make this code work!
 */

class NeighborCache {
	
	/* The states of neighbors in the cache.
	 */
	public final static int ATTEMPT1 = 0;		/* Discovery sequence */
	public final static int ATTEMPT2 = 1;
	public final static int ATTEMPT3 = 2;
	public final static int FAILED = 3;
	public final static int REACHABLE = 4;		/* Confirmed in the last ~30s */
	public final static int STALE = 5;			/* Not used for up to ~30s */
	public final static int NUM_NGB_STATES = 6;	/* Count of normal states */
	public final static int INTRANSIT = 7;		/* Traveling between queues */
	
	
	/* The timing spent in the various states of a cache entry.  Stable states
	 * take up several seconds, while waiting for a response is faster.
	 * Note the remarks at NeighborQueue.dequeue() for an important constraint;
	 * specifically, STALE is spent in an attempt to recycle an entry as
	 * REACHABLE, so the time spent in STALE must not exceed the time spent
	 * in REACHABLE.  This avoids having two entries for one neighbor in a
	 * queue, which would jeapourdise the timeout calculations.  Note that
	 * this problem does not arise when an object occurs in multiple queues
	 * at once, because a mismatch with the queue's state means that those
	 * lingering neighbors are ignored as timeout sources.
	 * 
	 * TODO: I found that Android can be pretty slow in responding over eth0,
	 * possibly worse over UMTS or (shiver) GPRS.  So, it may be that the
	 * following timing must be slowed down, *or* that NgbAdv ought to be
	 * accepted in the STALE state.
	 */
	final static int state_timing_millis [] = {
			50, 50, 50, 30000,				/* Discovery -- a few ms between steps */
			27500,							/* STALE -- 30 additional seconds */
			30000							/* REACHABLE -- 30 seconds of bliss */
	};
	
	/* The timer queues for neighbor discovery, each in its own thread */
	NeighborQueue queues [];
	
	/* The neighbor cache contains all elements, regardless of their state.
	 * This is needed to avoid doing double work.  If a neighbor is considered
	 * to be available, then it should be in here.  Once it stops being of
	 * interest (STALE or FAILED expires) it will be removed.
	 * 
	 * Note that the key and value are both Neighbor objects.  The only part
	 * of the Neighbor dictating equality (and the hashCode) is the 6-byte
	 * key holding the remote peer's IPv4 address and UDP port, so it is
	 * possible to put one object into the Hashtable as both key and value;
	 * and then to lookup an entry with a minimally setup Neighbor as key
	 * to find a more evolved value; the latter will be incorporated into
	 * the procedures of the Neighbor Cache, including the various queues.
	 */
	private Hashtable <Integer, Neighbor> neighbor_cache;
	
	
	/* The public server, used as default return value for queires after
	 * unsettled neighbor cache entries.
	 */
	private InetSocketAddress public_server;
	
	
	/* The socket used as uplink to the rest of the 6bed4-using World.
	 */
	private DatagramSocket uplink;
	
	
	/* The 6bed4 address as a 16-byte array; may also be used as an 8-byte prefix.
	 */
	private byte address_6bed4 [];
	
	/* The 6bed4 address as a 6-byte key.
	 */
	private byte address_6bed4_key [];
	
	
	/* Construct a neighbor cache with no entries but a lot of structure */
	public NeighborCache (DatagramSocket ipv4_uplink, InetSocketAddress pubserver, byte link_address_6bed4 []) {
		uplink = ipv4_uplink;
		public_server = pubserver;
		address_6bed4 = new byte [16];
		TunnelService.memcp_address (address_6bed4, 0, link_address_6bed4, 0);
		address_6bed4_key = new byte [6];
		address2key (address_6bed4, 0, address_6bed4_key);
		queues = new NeighborQueue [NUM_NGB_STATES];
		for (int i=0; i < NUM_NGB_STATES; i++) {
			queues [i] = new NeighborQueue (this, i);
		}
		neighbor_cache = new Hashtable <Integer,Neighbor> ();
	}
	
	/* Destroy a neighbor cache */
	public void cleanup () {
		for (int i=0; i < NUM_NGB_STATES; i++) {
			queues [i].interrupt ();
		}
	}

	/* Remove an expired entry from the neighbor cache.
	 */
	public void remove_from_cache (Neighbor ngb) {
		byte key [] = new byte [6];
		socketaddress2key (ngb.target, key);
		synchronized (neighbor_cache) {
			neighbor_cache.remove (key);
		}
	}
	
	/* Attempt to retrieve an entry from the neighbor cache.  The resulting
	 * actions as well as the return value may differ, depending on the
	 * state of the cache entry.  This function always returns immediately,
	 * with the tunnel server as a default response for instant connections,
	 * but the cache may asynchronously attempt to find a direct path to the
	 * neighbor.
	 * 
	 * The "playful" argument is rather special.  When set to true, it
	 * indicates that an initial experiment attempting to contact the
	 * neighbor directly is acceptable.  This is usually the cae if the
	 * following conditions apply:
	 *  1. The message is resent upon failure
	 *  2. Success can be recognised and reported back here
	 *  Setting this flag causes the first (and only the first) result
	 *  from lookup_neighbor to point directly to the host; resends will
	 *  be sent through the tunnel, while a few neighbor discoveries are
	 *  still tried towards the remote peer, until something comes back
	 *  directly to acknowledge contact.  When something comes back over
	 *  a direct route, this should be reported to the neighbor cache
	 *  through received_peer_direct_acknowledgement() so it can
	 *  learn that future traffic can be directly sent to the peer.
	 *  
	 * A common case for which this holds is TCP connection setup:
	 *  1. Packets with SYN are sent repeatedly if need be
	 *  2. Packets with ACK stand out in a crowd
	 * So, when TCP packets with SYN are sent, their lookup_neighbor()
	 * invocation can set the playful flag; the first attempt will go
	 * directly to the peer and hopefully initiate direct contact; if
	 * not, future attempts are sent through the tunnel.  When the
	 * remote peer accepts, it will return a packet with ACK set over
	 * the direct route, which is enough reason to signal success to
	 * the neighbor cache.  Interestingly, the first reply message
	 * from a TCP server will generally hold the SYN flag as well, so
	 * if the remote 6bed4 stack is also playful about TCP, it is
	 * likely to send it back directly even if it received the opening
	 * SYN through the tunnel.  This means that even a blocking NAT on
	 * the remote end will cause a new attempt on return.  As usual,
	 * an ACK from client to server follows, which asserts to the
	 * server that it can talk directly (because, at that time, the
	 * client has picked up on the directly sent SYN+ACK).
	 * 
	 * It is possible for TCP to initiate a connection from both ends
	 * at the same time.  Effectively this splits the SYN+ACK into
	 * two messages, one with SYN and one with ACK.  It may happen
	 * that direct contact is not correctly established if that is
	 * done, but the follow-up neighbor discovery that follow on a
	 * playful SYN that does not return an ACK over the direct route
	 * will establish NAT-passthrough if it is technically possible.
	 * A similar thing occurs when the initial SYN is lost for some
	 * reason. 
	 */
	public InetSocketAddress lookup_neighbor (byte addr [], int addrofs, boolean playful) {
		if (!is6bed4 (addr, addrofs)) {
			return public_server;
		}
		boolean puppy = false;
		Neighbor ngb;
		byte seeker_key [] = new byte [6];
		address2key (addr, addrofs, seeker_key);	//TODO// Only used for seeker creation?
		int seeker_hash = key2hash (seeker_key);
		synchronized (neighbor_cache) {
			ngb = neighbor_cache.get (seeker_hash);
			if (ngb == null) {
				ngb = new Neighbor (seeker_key, playful);	//TODO// Possibly seeker.clone ()
				neighbor_cache.put (seeker_hash, ngb);
				puppy = true;
			}
		}
		switch (ngb.state) {
		case ATTEMPT1:
		case ATTEMPT2:
		case ATTEMPT3:
		case FAILED:
		case INTRANSIT:
			//
			// No usable entry exists, but no new attempts are needed.
			// Simply return the default router's address.
			//
			// If the new entry was just inserted into the neighbor
			// cache for this thread, then enqueue into ATTEMPT1, part
			// of which is sending a neighbor solicitation.  However,
			// if the  request is made by a playful puppy, then skip
			// sending the Neighbor Discovery upon insertion into the
			// ATTEMPT1 queue; the 6bed4 stack will instead send the
			// initial message directly to the peer.  To that end, return
			// the direct neighbor instead of the safe default of the
			// public server.  Note that a repeat of the packet will
			// no longer count as a puppy, so this is done only once.
			if (puppy) {
				queues [ATTEMPT1].enqueue (ngb, playful);
				if (playful) {
					return ngb.target;
				}
			}
			return public_server;
		case STALE:
			//
			// The neighbor has been reached directly before; assume this
			// is still possible (continue into REACHABLE) but also send a
			// neighbor solicitation to keep the lines open.
			solicit_neighbor (ngb);
		case REACHABLE:
			//
			// The neighbor is known to be available for direct contact.
			// There is no work to be done to that end.
			return ngb.target;
		default:
			return public_server;
		}
	}
	
	/* Send a Neighbor Solicitation to the peer.  When this succeeds at
	 * penetrating to the remote peer's 6bed4 stack, then a Neighbor
	 * Advertisement will return, and be reported through the method
	 * NeighborCache.received_peer_direct_acknowledgement() so the
	 * cache can update its state, and support direct sending to the
	 * remote peer.  
	 * 
	 * Even though this kind of message may be sent after failed
	 * playful attempts, it still does not rule out the possibility
	 * of any such attempts returning, so the "playful" flag for
	 * the Neighbor solicited is not changed.
	 */
	public void solicit_neighbor (Neighbor ngb) {
		byte key [] = new byte [6];
		socketaddress2key (ngb.target, key);
		byte ngbsol [] = new byte [72];
		//
		// IPv6 header:
		ngbsol [0] = 0x60;
		ngbsol [1] = ngbsol [2] = ngbsol [3] = 0;
		ngbsol [4] = 0; ngbsol [5] = 4 + 28;
		ngbsol [6] = TunnelService.IPPROTO_ICMPV6;
		ngbsol [7] = (byte) 255;
		TunnelService.memcp_address (ngbsol, 8, address_6bed4, 0);
		key2ipv6address (key, ngbsol, 24);
		int ofs = 40;
		// ICMPv6 header:
		ngbsol [ofs++] = (byte) TunnelService.ND_NEIGHBOR_SOLICIT;
		ngbsol [ofs++] = 0;
		ofs += 2;	// Checksum
		ngbsol [ofs++] =
		ngbsol [ofs++] =
		ngbsol [ofs++] =
		ngbsol [ofs++] = 0x00;
		key2ipv6address (ngb.key, ngbsol, ofs);
		ofs += 16;
		// ICMPv6 Option: Source Link-Layer Address
		ngbsol [ofs++] = 1;	// SrcLLAddr
		ngbsol [ofs++] = 1;	// length is 1x 8 bytes
		for (int i=0; i<6; i++) {
			ngbsol [ofs++] = address_6bed4_key [i];
		}
		int csum = TunnelService.checksum_icmpv6 (ngbsol, 0);
		ngbsol [42] = (byte) ((csum >> 8) & 0xff);
		ngbsol [43] = (byte) ( csum       & 0xff);
		try {
			DatagramPacket pkt = new DatagramPacket (ngbsol, ngbsol.length, ngb.target);
			uplink.send (pkt);
		} catch (IOException ioe) {
			;
		}
	}
	
	/* The TunnelServer reports having received a neighbor advertisement
	 * by calling this function.  This may signal the cache that the
	 * corresponding cache entry needs updating.
	 * 
	 * The "playful" flag is used to indicate that the acknowledgement
	 * arrived as part of a playful attempt in lookup_neighbor().  This
	 * information is used to determine if resends have taken place
	 * through the tunnel server.  If such resends were sent, then it
	 * is not safe anymore to assume that a one-to-one exchange has
	 * taken place, and it becomes necessary to rely on the generic
	 * mechanism of Neighbor Discovery to detect the ability for direct
	 * contact between peers.
	 */
	public void received_peer_direct_acknowledgement (byte addr [], int addrofs, boolean playful) {
		Neighbor ngb;
		byte key [] = new byte [6];
		address2key (addr, addrofs, key);
		int seeker_hash = key2hash (key);
		synchronized (neighbor_cache) {
			ngb = neighbor_cache.get (seeker_hash);
		}
		if (ngb == null) {
			return;
		}
		if (playful && !ngb.playful) {
			return;
		}
		int nst = ngb.state;
		switch (nst) {
		case ATTEMPT1:
		case ATTEMPT2:
		case ATTEMPT3:
		case STALE:
			//
			// There is an entry waiting to be resolved.
			// Apply the lessons learnt to that entry.
			// Be careful -- another thread may be trying
			// the same, so we use the dequeue function
			// that checks again before proceeding.
			if (queues [nst].dequeue (ngb)) {
				//
				// Only one thread will continue here, having dequeued the neighbor
				queues [REACHABLE].enqueue (ngb);
			}
			break;
		case REACHABLE:
		case FAILED:
			//
			// A persistent state has been reached, and any
			// advertisements coming in have missed their
			// window of opportunity.  Note that this may be
			// a sign of attempted abuse.  So ignore it.
			break;
		case INTRANSIT:
			//
			// Some thread is already working on this neighbor, so drop
			// this extra notification.
			break;
		default:
			break;
		}
	}

	
	/* The Neighbor class represents individual entries in the NeighborCache.
	 * Each contains an IPv6 address as 16 bytes, its state, and a timeout for
	 * its current state queue.
	 * 
	 * Most of this class is storage, and is publicly accessible by the
	 * other components in this class/file.
	 * 
	 * Note that the Neighbor class serves in the hashtable as a key and value;
	 * but as a key, the only input used are the 6 bytes with the remote peer's
	 * IPv4 address and UDP port.  This means that another object can easily be
	 * constructed as a search entry, to resolve into the full entry.
	 * 
	 * TODO: Inner class -- does that mean that the context is copied into this object?  That'd be wasteful!
	 */
	static class Neighbor {
		protected int state;
		protected InetSocketAddress target;
		protected long timeout;
		byte key [];
		boolean playful;
		
		/* The hashCode for this object depends solely on the key value.
		 * Integrate all the bits of the key for optimal spreading.  The
		 * hashCode may only differ if the equals() output below also
		 * differs.
		 */
		public int hashCode () {
			return key2hash (key);
		}
		
		/* The equals output determines that two Neighbor objects are
		 * the same if their key matches; that is, if their remote
		 * IPv4 address and UDP port is the same.  It is guaranteed
		 * that the hashCode is the same for two equal objects; if two
		 * objects are unequal then their hashCode is *likely* to differ,
		 * but not guaranteed. 
		 */
		public boolean equals (Object oth) {
			try {
				Neighbor other = (Neighbor) oth;
				for (int i=0; i<5; i++) {
					if (this.key [i] != ((Neighbor) other).key [i]) {
						return false;
					}
				}
				return true;
			} catch (Throwable thr) {
				return false;
			}
		}
		
		/* Construct a new Neighbor based on its 6-byte key */
		public Neighbor (byte fromkey [], boolean create_playfully) {
			key = new byte [6];
			for (int i=0; i<6; i++) {
				key [i] = fromkey [i];
			}
			state = NeighborCache.ATTEMPT1;
			target = key2socketaddress (key);
			playful = create_playfully;
		}
	}
	
	/* The NeighborQueue inner class represents a timer queue.  The objects in this
	 * queue each have their own timeouts, as specified by state_timing_millis.
	 * New entries are attached to the back, timeouts are picked up at the front.
	 * 
	 * The queues have understanding of the various states, and how to handle
	 * expiration.
	 */
	class NeighborQueue extends Thread {
		
		private NeighborCache cache;
		private int whoami;
		private Queue <Neighbor> queue;
		
		public NeighborQueue (NeighborCache owner, int mystate) {
			cache = owner;
			whoami = mystate;
			queue = new ArrayDeque <Neighbor> ();
			this.start ();
		}
		
		public void run () {
			while (!isInterrupted ()) {
				//
				// First, fetch the head element (or wait for one to appear).
				// Sample its timeout.  Even if the head disappears later on,
				// any further entries are certain to have a later timeout.
				long timeout;
				synchronized (queue) {
					Neighbor head = null;
					while (head == null) {
						head = queue.peek ();
						if (head == null) {
							try {
								queue.wait ();
							} catch (InterruptedException ie) {
								return;
							}
						}
					}
					if (head.state == whoami) {
						timeout = 0;
					} else {
						timeout = head.timeout;
					}
				}
				//
				// Now wait until the timeout expires.  This is not done in
				// a synchronous fashion, so the queue can be changed as
				// desired by other threads.  Note that misplaced items
				// in the queue will be removed by the code further down
				// before this thread can find rest.
				if (timeout != 0) {
					try {
						sleep (timeout - System.currentTimeMillis ());
					} catch (InterruptedException ie) {
						return;
					}
				}
				//
				// Now the time has come to remove the head elements that
				// have expired.  If nothing happened to the head of the
				// queue, then this one should at least be removed, but
				// following entries may just as well need removal.  Do
				// this in a queue-synchronous manner to stop other threads
				// from altering the queue.
				long now = System.currentTimeMillis ();
				Queue <Neighbor> removed = new ArrayDeque <Neighbor> ();
				synchronized (queue) {
					Neighbor head = queue.peek ();
					while ((head != null) && ((head.state != whoami) || (head.timeout <= now))) {
						head = queue.remove ();
						if (head.state == whoami) {
							removed.offer (head);
						} // else, misplaced item that can be silently dropped
						head = queue.peek ();
					}
				}
				//
				// We now have a few elements in a temporary removed list;
				// handle those by inserting them into their next lifecycle
				// state (possibly meaning, destroy them).
				switch (whoami) {
				case ATTEMPT3:
/* TODO: Why would we want to remove the remote socket address?
					//
					// Remove the remote socket address, and continue with
					// promotion to the next lifecycle state
					for (Neighbor ngb : removed) {
						ngb.target = null;
					}
 */
				case ATTEMPT1:
				case ATTEMPT2:
				case REACHABLE:
					//
					// Promote the neighbor to its next lifecycle state
					for (Neighbor ngb : removed) {
						queues [whoami + 1].enqueue (ngb);
					}
					break;
				case FAILED:
				case STALE:
					//
					// Remove the neighbor from the cache
					for (Neighbor ngb : removed) {
						cache.remove_from_cache (ngb);
					}
					break;
				default:
					break;
				}
			}
		}

		/* Insert a new element into the queue.  If this is the first
		 * entry, be sure to notify the thread that may be waiting for
		 * something to chew on.
		 * Note that an entry may be enqueued only once, use dequeue
		 * to remove it from a possible former queue.
		 */
		public void enqueue (Neighbor ngb) {
			enqueue (ngb, false);
		}
		
		/* Insert a new element into the queue.  If this is the first
		 * entry, be sure to notify the thread that may be waiting for
		 * something to chew on.
		 * Note that an entry may be enqueued only once, use dequeue
		 * to remove it from a possible former queue.
		 * The flag "no_action" requests that the side-effects that
		 * may take place after inserting the element in the queue
		 * should be skipped.
		 */
		public void enqueue (Neighbor ngb, boolean no_action) {
			//
			// Initialise the neighbor for this queue
			ngb.state = whoami;
			ngb.timeout = System.currentTimeMillis () + NeighborCache.state_timing_millis [whoami];
			//
			// Insert the Neighbor into the queue
			synchronized (queue) {
				boolean wasEmpty = queue.isEmpty ();
				queue.offer (ngb);
				if (wasEmpty) {
					queue.notifyAll ();
				}
			}
			//
			// Skip the side-effect of insertion is so desired.
			if (no_action) {
				return;
			}
			//
			// If this queue represents waiting for a reply to Neighbor
			// Solicitation, then solicit the neighbor.  This is not
			// synchronous, because networking does not directly modify
			// the queue, is slow and is asynchronous in nature anyway.
			switch (whoami) {
			case ATTEMPT1:
			case ATTEMPT2:
			case ATTEMPT3:
				cache.solicit_neighbor (ngb);
				break;
			default:
				break;
			}
		}

		/* Remove an element from the queue without awaiting timer
		 * expiration.  This is only of interest when incoming traffic
		 * follows a direct route, while our previous attempts ran into
		 * FAILED because the remote didn't have its ports open for us yet.
		 * In addition, this may be used when a timer has gone STALE.
		 * Note that expiration timers on the queue can continue to run,
		 * they may simply find that the first element has not timed out
		 * and another cycle is needed for what is then the head.
		 * The state and timeout of the neighbor entry will not be modified
		 * by this call, but future enqueueing may do that nonetheless.
		 * The function returns success; that is, it tells the caller if
		 * the object was indeed found in the queue. 
		 * 
		 * *** Implementation note ***
		 * 
		 * Dequeueing is expensive, as it involves going through the list
		 * of stored items.  Keeping the structures up to date to speedup
		 * this operation is hardly more pleasant.  So, we'll use a trick.
		 * We simply won't dequeue.  This means that queues can hold items
		 * that should have been removed.  As long as objects never cycle
		 * back to this queue before their timers in this queue have
		 * expired, the misplacement of a queue item could be directly
		 * inferred from the state, which does not match the queue's state.
		 * Such misplaced items can be silently removed.  Doing this, the
		 * only remaining task for dequeue is to change the state in an
		 * atomic fashion -- that is, without risk of overlapping similar
		 * operations by other threads.  As originally intended, there
		 * must be only one thread that receives true when removing an
		 * item from a queue that holds it.  The loop that removes the
		 * queue elements from the head also changes to accommodate this
		 * behaviour.    
		 */
		public boolean dequeue (Neighbor ngb) {
			boolean wasthere;
			synchronized (queue) {
				if (ngb.state != whoami) {
					wasthere = false;
				} else {
					ngb.state = NeighborCache.INTRANSIT;
					wasthere = true;
				}
			}
			return wasthere;
		}
	}

	
	/***
	 *** UTILITIES
	 ***/
	
	
	/* Check if an address is a 6bed4 address.
	 */
	public boolean is6bed4 (byte addr [], int addrofs) {
		if (TunnelService.memdiff_halfaddr (addr, addrofs, address_6bed4, 0)) {
			return false;
		}
		//TODO// Possibly require that the port number is even
		return true;
	}
	
	/* Map an address to a 6-byte key in the provided space.  Assume that the
	 * address is known to be a 6bed4 address.
	 */
	private static void address2key (byte addr [], int addrofs, byte key []) {
		// UDP port
		key [0] = addr [addrofs + 13];
		key [1] = addr [addrofs + 12];
		// IPv4 address
		key [2] = (byte) (addr [addrofs + 8] & 0xfc | (addr [addrofs + 14] & 0xff) >> 6);
		key [3] = addr [addrofs + 9];
		key [4] = addr [addrofs + 10];
		key [5] = addr [addrofs + 11];
	}

	/* Map an InetSocketAddress to a 6-byte key in the provided space.
	 */
	private static void socketaddress2key (InetSocketAddress sa, byte key []) {
		int port = sa.getPort ();
		byte addr [] = sa.getAddress ().getAddress ();
		key [0] = (byte) (port & 0xff);
		key [1] = (byte) (port >> 8  );
		key [2] = addr [0];
		key [3] = addr [1];
		key [4] = addr [2];
		key [5] = addr [3];
	}

	/* Map a key to an IPv6 address, following the 6bed4 structures.
	 */
	private void key2ipv6address (byte key [], byte addr [], int addrofs) {
		for (int i=0; i<8; i++) {
			addr [addrofs + i] = address_6bed4 [i];
		}
		addr [addrofs +  8] = (byte) (key [2] & 0xfc);
		addr [addrofs +  9] = key [3];
		addr [addrofs + 10] = key [4];
		addr [addrofs + 11] = key [5];
		addr [addrofs + 12] = key [1];
		addr [addrofs + 13] = key [0];
		addr [addrofs + 14] = (byte) ((key [2] & 0xff) << 6);
		addr [addrofs + 15] = 1;
	}

	/* Map a key to an InetSocketAddress.
	 */
	private static InetSocketAddress key2socketaddress (byte key []) {
		int port = (int) (key [0] & 0xff);
		port = port | (((int) (key [1] << 8)) & 0xff00);
		byte v4addr [] = new byte [4];
		v4addr [0] = key [2];
		v4addr [1] = key [3];
		v4addr [2] = key [4];
		v4addr [3] = key [5];
		try {
			Inet4Address remote = (Inet4Address) Inet4Address.getByAddress (v4addr);
			return new InetSocketAddress (remote, port);						
		} catch (UnknownHostException uhe) {
			throw new RuntimeException ("Internet Error", uhe);
		}
	}
	
	private static int key2hash (byte key []) {
		int retval;
		retval = key [0] ^ key [3];
		retval <<= 8;
		retval += key [1] ^ key [4];
		retval <<= 8;
		retval += key [2] ^ key [5];
		return retval;
	}

}
