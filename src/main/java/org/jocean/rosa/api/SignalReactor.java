package org.jocean.rosa.api;

public interface SignalReactor<RESPONSE> {
	
	/**
	 * special response received and decode succeed
	 * @param response
	 * @throws Exception
	 */
	public void onResponseReceived(final RESPONSE response) throws Exception;
	
	/**
	 * signal transaction finished succeed or failed(timeout | deocde failed)
	 * @throws Exception
	 */
	public void onTransactionFinished(final int status) throws Exception;
}
