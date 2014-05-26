/**
 * 
 */
package org.jocean.ssl;

import java.nio.ByteBuffer;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
public class FixNeverReachFINISHEDStateSSLEngine extends SSLEngine {

	private static final Logger LOG =
			LoggerFactory.getLogger("FixNeverReachFINISHEDStateSSLEngine");
	
	/* (non-Javadoc)
	 * @see javax.net.ssl.SSLEngine#beginHandshake()
	 */
	@Override
	public void beginHandshake() throws SSLException {
		this._engine.beginHandshake();
	}

	/* (non-Javadoc)
	 * @see javax.net.ssl.SSLEngine#closeInbound()
	 */
	@Override
	public void closeInbound() throws SSLException {
		this._engine.closeInbound();
	}

	/* (non-Javadoc)
	 * @see javax.net.ssl.SSLEngine#closeOutbound()
	 */
	@Override
	public void closeOutbound() {
		this._engine.closeOutbound();
	}

	/* (non-Javadoc)
	 * @see javax.net.ssl.SSLEngine#getDelegatedTask()
	 */
	@Override
	public Runnable getDelegatedTask() {
		return this._engine.getDelegatedTask();
	}

	/* (non-Javadoc)
	 * @see javax.net.ssl.SSLEngine#getEnabledCipherSuites()
	 */
	@Override
	public String[] getEnabledCipherSuites() {
		return this._engine.getEnabledCipherSuites();
	}

	/* (non-Javadoc)
	 * @see javax.net.ssl.SSLEngine#getEnabledProtocols()
	 */
	@Override
	public String[] getEnabledProtocols() {
		return this._engine.getEnabledProtocols();
	}

	/* (non-Javadoc)
	 * @see javax.net.ssl.SSLEngine#getEnableSessionCreation()
	 */
	@Override
	public boolean getEnableSessionCreation() {
		return this._engine.getEnableSessionCreation();
	}

	/* (non-Javadoc)
	 * @see javax.net.ssl.SSLEngine#getHandshakeStatus()
	 */
	@Override
	public HandshakeStatus getHandshakeStatus() {
		return this._engine.getHandshakeStatus();
	}

	/* (non-Javadoc)
	 * @see javax.net.ssl.SSLEngine#getNeedClientAuth()
	 */
	@Override
	public boolean getNeedClientAuth() {
		return this._engine.getNeedClientAuth();
	}

	/* (non-Javadoc)
	 * @see javax.net.ssl.SSLEngine#getSession()
	 */
	@Override
	public SSLSession getSession() {
		return this._engine.getSession();
	}

	/* (non-Javadoc)
	 * @see javax.net.ssl.SSLEngine#getSupportedCipherSuites()
	 */
	@Override
	public String[] getSupportedCipherSuites() {
		return this._engine.getSupportedCipherSuites();
	}

	/* (non-Javadoc)
	 * @see javax.net.ssl.SSLEngine#getSupportedProtocols()
	 */
	@Override
	public String[] getSupportedProtocols() {
		return this._engine.getSupportedProtocols();
	}

	/* (non-Javadoc)
	 * @see javax.net.ssl.SSLEngine#getUseClientMode()
	 */
	@Override
	public boolean getUseClientMode() {
		return this._engine.getUseClientMode();
	}

	/* (non-Javadoc)
	 * @see javax.net.ssl.SSLEngine#getWantClientAuth()
	 */
	@Override
	public boolean getWantClientAuth() {
		return this._engine.getWantClientAuth();
	}

	/* (non-Javadoc)
	 * @see javax.net.ssl.SSLEngine#isInboundDone()
	 */
	@Override
	public boolean isInboundDone() {
		return this._engine.isInboundDone();
	}

	/* (non-Javadoc)
	 * @see javax.net.ssl.SSLEngine#isOutboundDone()
	 */
	@Override
	public boolean isOutboundDone() {
		return this._engine.isOutboundDone();
	}

	/* (non-Javadoc)
	 * @see javax.net.ssl.SSLEngine#setEnabledCipherSuites(java.lang.String[])
	 */
	@Override
	public void setEnabledCipherSuites(String[] suites) {
		this._engine.setEnabledCipherSuites(suites);
	}

	/* (non-Javadoc)
	 * @see javax.net.ssl.SSLEngine#setEnabledProtocols(java.lang.String[])
	 */
	@Override
	public void setEnabledProtocols(String[] protocols) {
		this._engine.setEnabledProtocols(protocols);
	}

	/* (non-Javadoc)
	 * @see javax.net.ssl.SSLEngine#setEnableSessionCreation(boolean)
	 */
	@Override
	public void setEnableSessionCreation(boolean flag) {
		this._engine.setEnableSessionCreation(flag);
	}

	/* (non-Javadoc)
	 * @see javax.net.ssl.SSLEngine#setNeedClientAuth(boolean)
	 */
	@Override
	public void setNeedClientAuth(boolean need) {
		this._engine.setNeedClientAuth(need);
	}

	/* (non-Javadoc)
	 * @see javax.net.ssl.SSLEngine#setUseClientMode(boolean)
	 */
	@Override
	public void setUseClientMode(boolean mode) {
		this._engine.setUseClientMode(mode);
	}

	/* (non-Javadoc)
	 * @see javax.net.ssl.SSLEngine#setWantClientAuth(boolean)
	 */
	@Override
	public void setWantClientAuth(boolean want) {
		this._engine.setWantClientAuth(want);
	}

	/* (non-Javadoc)
	 * @see javax.net.ssl.SSLEngine#unwrap(java.nio.ByteBuffer, java.nio.ByteBuffer[], int, int)
	 */
	@Override
	public SSLEngineResult unwrap(final ByteBuffer src, final ByteBuffer[] dsts,
			final int offset, final int length) throws SSLException {
		final SSLEngineResult result = this._engine.unwrap(src, dsts, offset, length);
		if ( safeResultHandshakeStatusEquals( this._lastUnwrapResult, HandshakeStatus.NEED_UNWRAP) 
			&& safeResultHandshakeStatusEquals( result, HandshakeStatus.NOT_HANDSHAKING)) {
			_lastUnwrapResult =	new SSLEngineResult(result.getStatus(), HandshakeStatus.FINISHED, result.bytesConsumed(), result.bytesProduced());
			if ( LOG.isDebugEnabled()) {
				LOG.debug("replace (NEED_UNWRAP ==>)NOT_HANDSHAKING with FINISHED handshake status.");
			}
		}
		else {
			_lastUnwrapResult = result;
		}
		return	_lastUnwrapResult;
	}

	/* (non-Javadoc)
	 * @see javax.net.ssl.SSLEngine#wrap(java.nio.ByteBuffer[], int, int, java.nio.ByteBuffer)
	 */
	@Override
	public SSLEngineResult wrap(final ByteBuffer[] srcs, final int offset, final int length,
			final ByteBuffer dst) throws SSLException {
		final SSLEngineResult result = this._engine.wrap(srcs, offset, length, dst);
		if ( safeResultHandshakeStatusEquals( this._lastWrapResult, HandshakeStatus.NEED_WRAP)
			&& safeResultHandshakeStatusEquals(result, HandshakeStatus.NOT_HANDSHAKING)) {
			_lastWrapResult = new SSLEngineResult(result.getStatus(), HandshakeStatus.FINISHED, result.bytesConsumed(), result.bytesProduced());
			if ( LOG.isDebugEnabled() ) {
				LOG.debug("replace (NEED_WRAP ==>)NOT_HANDSHAKING with FINISHED handshake status.");
			}
		}
		else {
			_lastWrapResult = result;
		}
		return	_lastWrapResult;
	}

	private static boolean safeResultHandshakeStatusEquals(final SSLEngineResult result, final HandshakeStatus handshakeStatus ) {
		return (null != result) && result.getHandshakeStatus().equals(handshakeStatus);
	}
	
	private FixNeverReachFINISHEDStateSSLEngine(final SSLEngine engine) {
		this._engine = engine;
	}
	
	public static SSLEngine fixAndroidBug(final SSLEngine engine) {
		return	new FixNeverReachFINISHEDStateSSLEngine(engine);
	}

	private final SSLEngine _engine;
	private volatile SSLEngineResult _lastUnwrapResult;
	private volatile SSLEngineResult _lastWrapResult;
}
