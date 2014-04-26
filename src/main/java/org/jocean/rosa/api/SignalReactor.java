package org.jocean.rosa.api;

public interface SignalReactor<RESPONSE> {
	
	/**
	 * special response received and decode succeed
	 * @param response
	 * @throws Exception
	 */
	public void onResponseReceived(final RESPONSE response) throws Exception;
	
	/**
	 * signal transaction failed(timeout | decode failed)
	 * @throws Exception
	 */
	public void onTransactionFailure(final int failureReason) throws Exception;
}
