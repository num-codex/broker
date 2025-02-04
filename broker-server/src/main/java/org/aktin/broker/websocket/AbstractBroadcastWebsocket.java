package org.aktin.broker.websocket;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Level;

import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.PongMessage;
import javax.websocket.Session;

import org.aktin.broker.auth.Principal;

import lombok.extern.java.Log;

@Log
public abstract class AbstractBroadcastWebsocket {

	protected abstract boolean isAuthorized(Principal principal);
	protected abstract void addSession(Session session, Principal user);
	protected abstract void removeSession(Session session, Principal user);


	@OnOpen
	public void open(Session session){
		Principal user = getSessionPrincipal(session);
		log.log(Level.INFO, "Websocket session {0} created for user {1}", new Object[] {session.getId(), user});

		// check privileges and close session if needed
		if( isAuthorized(user) ) {
			addSession(session, user);

		}else {
			// unauthorized, close session
			try {
				session.close();
				return;
			} catch (IOException e) {
				log.log(Level.WARNING,"Failed to close session", e);
			}
		}
		// send welcome message
		try {
			session.getBasicRemote().sendText("welcome "+user.getName());
		} catch (IOException e) {
			log.log(Level.WARNING,"Unable to send welcome message", e);
		}
	}
	@OnClose
	public void close(Session session){
		Principal user = getSessionPrincipal(session);
		removeSession(session, user);
		log.log(Level.INFO,"Websocket session {0} closed for user {1} ",new Object[] {session.getId(), user});
	}

	@OnMessage
	public void message(Session session, String message){
		Principal user = getSessionPrincipal(session);
		if( message.startsWith("ping ") ) {
			// send pong
			try {
				session.getBasicRemote().sendText("pong "+message.substring(5));
				log.log(Level.INFO, "Websocket ping reply sent for session {0} user {1}", new Object[] {session.getId(), user});
			} catch (IOException e) {
				log.log(Level.WARNING, "Websocket ping pong reply failed for user "+user, e);
			}
		}else {
			log.log(Level.INFO, "Ignoring message from user {0}", user);
		}
	}


	@OnMessage
	public void message(Session session, PongMessage message){
		Principal user = getSessionPrincipal(session);
	    log.log(Level.INFO, "Websocket pong message for session {0} user {1} length {2}", new Object[] {session.getId(), user, message.getApplicationData().remaining()});
	}
	@OnError
	public void error(Session session, Throwable t) {
		Principal user = getSessionPrincipal(session);
	    log.log(Level.INFO, "Websocket session {0} error for user {1}: {2}", new Object[] {session.getId(), user, t});
	}

	static int broadcast(Set<Session> clients, String message){
		// if no filter is supplied, broadcast to all nodes
		return broadcast(clients, message, p -> true);
	}

	static int broadcast(Set<Session> clients, String message, Predicate<Principal> principalFilter){
		Objects.requireNonNull(principalFilter);
		if( clients.isEmpty() ){
			return 0;
		}
		int count = 0;
		// loop through connected clients
		for( Session session : clients ){
			Principal user = getSessionPrincipal(session);
			if( user == null ) {
				log.log(Level.WARNING,"Skipping websocket session {0} without authentication",session.getId());
				continue;
			}
			if( principalFilter.test(user) == false ) {
				// skip filtered
				continue;
			}
			if( session.isOpen() ){
				session.getAsyncRemote().sendText(message);
				count ++;
			}
		}
		return count;
	}

	/**
	 * Get authentication info for a given websocket session
	 * @param session session
	 * @return principal
	 */
	protected static Principal getSessionPrincipal(Session session) {
		return (Principal)session.getUserProperties().get(HeaderAuthSessionConfigurator.AUTH_USER);
	}

}
