package nl.openfortress.android6bed4;

import java.util.Timer;
import java.util.TimerTask;
import java.io.IOException;
import java.net.*;


public class KeepAliveService extends TimerTask {

	Timer tim;
	DatagramSocket sox;
	DatagramPacket keepalive;
	int ttl_normal = 30;
	
	public KeepAliveService (DatagramSocket local, InetSocketAddress server) {
		byte payload [] = { };
		tim = null;
		sox = local;
		// ttl_normal = sox.getTimeToLive();
		try {
			keepalive = new DatagramPacket (payload, 0, server);
		} catch (SocketException se) {
			keepalive = null;
		}
	}
	
	synchronized public void start () {
		if (tim == null) {
			tim = new Timer ("6bed4 KeepAlive ticker", true);
			tim.scheduleAtFixedRate (this, 0, 30000);
		}
	}
	
	synchronized public void stop () {
		if (tim != null) {
			tim.cancel ();
			tim = null;
		}
	}
	
	@Override
	synchronized public void run () {
		if ((tim != null) && (keepalive != null)) {
			try {
				//TODO// sox.setTimeToLive (3);
				sox.send (keepalive);
				// sox.setTimeToLive (ttl_normal);
			} catch (IOException ioe) {
				;
			}
		}
	}

}
