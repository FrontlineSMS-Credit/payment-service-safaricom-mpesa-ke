package net.frontlinesms.payment.safaricom;

import net.frontlinesms.events.FrontlineEventNotification;

public class BalanceEventNotification implements FrontlineEventNotification {
	private final String message;
	
	public BalanceEventNotification(MpesaPaymentService s) {
		this.message = String.format("%s New Balance is: %s (%s)", s.getBalanceConfirmationCode(), 
				s.getBalanceAmount().toString(),
				s.getSettings().getId());
	}
	
	public String getMessage() {
		return this.message;
	}
}