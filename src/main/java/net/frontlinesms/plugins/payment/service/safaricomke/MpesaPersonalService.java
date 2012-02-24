package net.frontlinesms.plugins.payment.service.safaricomke;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import net.frontlinesms.data.DuplicateKeyException;
import net.frontlinesms.data.domain.FrontlineMessage;
import net.frontlinesms.plugins.payment.service.PaymentJob;
import net.frontlinesms.plugins.payment.service.PaymentServiceException;
import net.frontlinesms.plugins.payment.service.PaymentStatus;
import net.frontlinesms.plugins.payment.service.ui.PaymentServiceUiActionHandler;
import net.frontlinesms.serviceconfig.ConfigurableServiceProperties;
import net.frontlinesms.ui.UiGeneratorController;

import org.creditsms.plugins.paymentview.data.domain.Account;
import org.creditsms.plugins.paymentview.data.domain.Client;
import org.creditsms.plugins.paymentview.data.domain.OutgoingPayment;
import org.smslib.CService;
import org.smslib.SMSLibDeviceException;
import org.smslib.handler.ATHandler.SynchronizedWorkflow;
import org.smslib.stk.StkConfirmationPrompt;
import org.smslib.stk.StkMenu;
import org.smslib.stk.StkResponse;
import org.smslib.stk.StkValuePrompt;

@ConfigurableServiceProperties(name="MPESA Kenya Personal", icon="/icons/mpesa_ke_personal.png")
public class MpesaPersonalService extends MpesaPaymentService {
//> MESSAGE CONTENT MATCHER CONSTANTS
	private static final String INCOMING_PAYMENT_REGEX = "[A-Z0-9]+ Confirmed.\n" +
			"You have received Ksh[,|.|\\d]+ from\n([A-Za-z ]+) 2547[\\d]{8}\non " +
			"(([1-2]?[1-9]|[1-2]0|3[0-1])/([1-9]|1[0-2])/(1[0-2])) (at) ([1]?\\d:[0-5]\\d) (AM|PM)\n" +
			"New M-PESA balance is Ksh[,|.|\\d]+";
	/** example matching string: Failed. M-PESA cannot send Ksh67,100.00 to 254704593656. For more information call or SMS customer services on 234. */
	private static final String SENDING_PAYMENT_TO_SAME_ACCOUNT_REGEX  = 
			"Failed. M-PESA cannot send Ksh([,|.|\\d]+) to 2547[\\d]{8}. " +
			"For more information call or SMS customer services on \\d{3}";
	private static final String OUTGOING_PAYMENT_INSUFFICIENT_FUNDS_REGEX  =
			"Failed. \nNot enough money in your M-PESA account to send Ksh[,|.|\\d]+.00. " +
			"You must be able to pay the transaction fee as well as the requested " +
			"amount.\nYour M-PESA balance is Ksh[,|.|\\d]+.00";
	
	private static final String OUTGOING_PAYMENT_INACTIVE_PAYBILL_REGEX  =
		"Failed. M-PESA cannot  pay Ksh[,|.|\\d]+.00 " +
		"to ([A-Za-z ]+).";
	
	private static final String OUTGOING_PAYMENT_UNREGISTERED_USER_REGEX = "[A-Z0-9]+ Confirmed. Ksh[,|.|\\d]+ " +
	"sent to (\\+254[\\d]{9}|[\\d|-]) on " +
	"(([1-2]?[1-9]|[1-2]0|3[0-1])/([1-9]|1[0-2])/(1[0-2])) at ([1]?\\d:[0-5]\\d) ([A|P]M)(\\n| )" +
	"New M-PESA balance is Ksh([,|.|\\d]+)";
	
	private static final String OUTGOING_PAYMENT_PAYBILL_REGEX = "[A-Z0-9]+ Confirmed. Ksh[,|.|\\d]+ " +
			"sent to ([A-Za-z ]+) for account ([\\d]+) on " +
			"(([1-2]?[1-9]|[1-2]0|3[0-1])/([1-9]|1[0-2])/(1[0-2])) at ([1]?\\d:[0-5]\\d) ([A|P]M)(\\n| )" +
			"New M-PESA balance is Ksh([,|.|\\d]+)";
	
	private static final String MPESA_PAYMENT_FAILURE_REGEX = "";
	private static final String LESS_THAN_MINIMUM_AMOUNT = "Failed. The amount is less than minimum M-PESA money transfer value.";
	private static final String OUTGOING_PAYMENT_REGEX = "[A-Z0-9]+ Confirmed. Ksh[,|.|\\d]+ "
			+ "sent to ([A-Za-z ]+) (\\+254[\\d]{9}|[\\d|-])+ "
			+ "on (([1-2]?[1-9]|[1-2]0|3[0-1])/([1-9]|1[0-2])/(1[0-2])) at ([1]?\\d:[0-5]\\d) ([A|P]M)(\\n| )"
			+ "New M-PESA balance is Ksh([,|.|\\d]+)";
	private static final String BALANCE_REGEX = "[A-Z0-9]+ Confirmed.\n"
			+ "Your M-PESA balance was Ksh([,|.|\\d]+)\n"
			+ "on (([1-2]?[1-9]|[1-2]0|3[0-1])/([1-9]|1[0-2])/(1[0-2])) at (([1]?\\d:[0-5]\\d) ([A|P]M))";

//> INSTANCE METHODS
	public boolean isOutgoingPaymentEnabled() {
		return super.isOutgoingPaymentEnabled();
	}
	
	public void makePayment(final OutgoingPayment outgoingPayment)
			throws PaymentServiceException {
		final CService cService = smsModem.getCService();
		final BigDecimal amount = outgoingPayment.getAmountPaid();
		queueOutgoingJob(new PaymentJob() {
			public void run() {
				try {
					cService.doSynchronized(new SynchronizedWorkflow<Object>() {
						public Object run() throws SMSLibDeviceException,
								IOException {

							initIfRequired();
							updateStatus(PaymentStatus.SENDING);
							final StkMenu mPesaMenu = getMpesaMenu();
							final StkResponse sendMoneyResponse = cService
									.stkRequest(mPesaMenu.getRequest("Send money"));

							StkValuePrompt enterPhoneNumberPrompt;
							if (sendMoneyResponse instanceof StkMenu) {
								enterPhoneNumberPrompt = (StkValuePrompt) cService
										.stkRequest(((StkMenu) sendMoneyResponse)
												.getRequest("Enter phone no."));
							} else {
								enterPhoneNumberPrompt = (StkValuePrompt) sendMoneyResponse;
							}

							final StkResponse enterPhoneNumberResponse = cService.stkRequest(
									enterPhoneNumberPrompt.getRequest(),
									outgoingPayment.getClient().getPhoneNumber());
							try {							
								if (!(enterPhoneNumberResponse instanceof StkValuePrompt)) {
									logDao.error("Phone number rejected", "");
									outgoingPayment.setStatus(OutgoingPayment.Status.ERROR);
	
									outgoingPaymentDao.updateOutgoingPayment(outgoingPayment);
									updateStatus(PaymentStatus.ERROR);
									throw new RuntimeException("Phone number rejected");
								}
								final StkResponse enterAmountResponse = cService
										.stkRequest(
												((StkValuePrompt) enterPhoneNumberResponse)
														.getRequest(), amount
														.toString());
								if (!(enterAmountResponse instanceof StkValuePrompt)) {
									logDao.error("amount rejected", "");
									outgoingPayment.setStatus(OutgoingPayment.Status.ERROR);
									outgoingPaymentDao.updateOutgoingPayment(outgoingPayment);
									updateStatus(PaymentStatus.ERROR);
									throw new RuntimeException("amount rejected");
								}
								final StkResponse enterPinResponse = cService
										.stkRequest(((StkValuePrompt) enterAmountResponse)
														.getRequest(), getPin());
								if (!(enterPinResponse instanceof StkConfirmationPrompt)) {
									logDao.error("PIN rejected", "");
									outgoingPayment.setStatus(OutgoingPayment.Status.ERROR);
									outgoingPaymentDao.updateOutgoingPayment(outgoingPayment);
									updateStatus(PaymentStatus.ERROR);
									throw new RuntimeException("PIN rejected");
								}
								cService.stkRequest(((StkConfirmationPrompt) enterPinResponse)
												.getRequest());
								outgoingPayment.setStatus(OutgoingPayment.Status.UNCONFIRMED);
								logDao.info("Outgoing Payment", outgoingPayment.toStringForLogs());
	
								outgoingPaymentDao.updateOutgoingPayment(outgoingPayment);
								updateStatus(PaymentStatus.COMPLETE);
							} catch (DuplicateKeyException e) {
								// TODO Auto-generated catch block
								updateStatus(PaymentStatus.ERROR);
								e.printStackTrace();
							}
							return null;
						}
					});
				} catch (Exception ex) {
					logDao.error("Payment failed due to " + ex.getClass().getSimpleName(), ex.getMessage());
					outgoingPayment.setStatus(OutgoingPayment.Status.ERROR);
					try {
						outgoingPaymentDao.updateOutgoingPayment(outgoingPayment);
					} catch (DuplicateKeyException e) {
						logDao.error("Unable to update payment: " + ex.getClass().getSimpleName(), ex.getMessage());
					}
					updateStatus(PaymentStatus.ERROR);
				}
			}
		});
	}
	
	@Override
	protected boolean isValidBalanceMessage(FrontlineMessage message) {
		return message.getTextContent().matches(BALANCE_REGEX);
	}

	@Override
	protected void processMessage(final FrontlineMessage message) {
		if (message.getEndpointId() != null) {
			if((getSimImsi()+"@"+getModemSerial()).equals(message.getEndpointId())) {
				if (isValidOutgoingPaymentPayBillConfirmation(message)) {
					processOutgoingPayBillPayment(message);
				} else if (isFailedMpesaPayment(message)) {
					logDao.error("Payment Message: Failed message",message.getTextContent());
				} else if (isValidOutgoingPaymentConfirmation(message)) {
					processOutgoingPayment(message);
				} else if (isValidOutgoingPaymentConfirmationForUnregisteredUser(message)) {
					processOutgoingPayment(message);
				} else if (isInactivePayBillAccount(message)) {
					logDao.error("Payment Message: Failed message",message.getTextContent());
				} else if (isBelowMinimumAmount(message)) {
					reportBelowMinimumAmount();
				} else if (isSentToSameAccountMessage(message)) {	
					reportSentToSameAccount(message);
				} else if (isInsufficientFundsMessage(message)) {	
					reportInsufficientFunds(message);
				} else {
					super.processMessage(message);
				} 
			}
		}
	}

	private void reportBelowMinimumAmount() {
		logDao.error("PAYMENT FAILED", "Payment Failed. Payment was less than minimum amount.");
	}
	
	private boolean isBelowMinimumAmount(final FrontlineMessage message) {
		if (!message.getSenderMsisdn().equals("MPESA")) {
			return false;
		}
		return message.getTextContent().contains(LESS_THAN_MINIMUM_AMOUNT);
	}
	
	private boolean isFailedMpesaPayment(final FrontlineMessage message) {
		return message.getTextContent().matches(MPESA_PAYMENT_FAILURE_REGEX);
	}

	private void reportInsufficientFunds(final FrontlineMessage message) {
		queueIncomingJob(new PaymentJob() {
			public void run() {
				try {
					// Retrieve the corresponding outgoing payment with status
					// UNCONFIRMED
					List<OutgoingPayment> outgoingPayments = new ArrayList<OutgoingPayment>();
					if (!getAmount(message).toString().equals("")){
						 outgoingPayments = outgoingPaymentDao
							.getByAmountPaidAndStatus(new BigDecimal(
											getAmount(message).toString()),
									OutgoingPayment.Status.UNCONFIRMED);
					}

					if (!outgoingPayments.isEmpty()) {
						final OutgoingPayment outgoingPayment = outgoingPayments.get(0);
						outgoingPayment.setStatus(OutgoingPayment.Status.ERROR);
						outgoingPaymentDao.updateOutgoingPayment(outgoingPayment);
						logDao.error("PAYMENT FAILED", "Insufficient funds in mobile money account.");
					} else {
						logDao.warn("Outgoing Confirmation Payment: No unconfirmed outgoing payment for the following confirmation message", message.getTextContent());
					}
				} catch (IllegalArgumentException ex) {
					logDao.error("Outgoing Confirmation Payment: Message failed to parse; likely incorrect format", message.getTextContent());
					throw new RuntimeException(ex);
				} catch (Exception ex) {
					logDao.error("Outgoing Confirmation Payment: Unexpected exception parsing outgoing payment SMS", message.getTextContent());
					throw new RuntimeException(ex);
				}
			}
		});
	}
	
	private void reportSentToSameAccount(final FrontlineMessage message) {
		queueIncomingJob(new PaymentJob() {
			public void run() {
				try {
					// Retrieve the corresponding outgoing payment with status
					// UNCONFIRMED
					List<OutgoingPayment> outgoingPayments = new ArrayList<OutgoingPayment>();
					if (!getPhoneNumber(message).equals("")){
						 outgoingPayments = outgoingPaymentDao
							.getByPhoneNumberAndAmountPaid(
									getPhoneNumber(message), new BigDecimal(
											getAmount(message).toString()),
									OutgoingPayment.Status.UNCONFIRMED);
					}

					if (!outgoingPayments.isEmpty()) {
						final OutgoingPayment outgoingPayment = outgoingPayments.get(0);
						outgoingPayment.setTimeConfirmed(getTimePaid(message, true).getTime());
						outgoingPayment.setStatus(OutgoingPayment.Status.ERROR);
						outgoingPaymentDao.updateOutgoingPayment(outgoingPayment);
						logDao.error("PAYMENT FAILED", "Sending funds to the same mobile money account.");
					} else {
						logDao.warn("Outgoing Confirmation Payment: No unconfirmed outgoing payment for the following confirmation message", message.getTextContent());
					}
				} catch (IllegalArgumentException ex) {
					logDao.error("Outgoing Confirmation Payment: Message failed to parse; likely incorrect format", message.getTextContent());
					throw new RuntimeException(ex);
				} catch (Exception ex) {
					logDao.error("Outgoing Confirmation Payment: Unexpected exception parsing outgoing payment SMS", message.getTextContent());
					throw new RuntimeException(ex);
				}
			}
		});
	}
	
	private void processOutgoingPayment(final FrontlineMessage message) {
		queueOutgoingJob(new PaymentJob() {
			public void run() {
				try {
					// Retrieve the corresponding outgoing payment with status
					// UNCONFIRMED
					List<OutgoingPayment> outgoingPayments;
					String phoneNumber = getPhoneNumber(message);
					BigDecimal amount = getAmount(message);
					
					if(phoneNumber.equals("")) {
					} else {
						outgoingPayments = outgoingPaymentDao.getByPhoneNumberAndAmountPaid(
								phoneNumber, amount, OutgoingPayment.Status.UNCONFIRMED);

						if (!outgoingPayments.isEmpty()) {
							final OutgoingPayment outgoingPayment = outgoingPayments.get(0);
							outgoingPayment.setConfirmationCode(getConfirmationCode(message));
							outgoingPayment.setTimeConfirmed(getTimePaid(message, true).getTime());
							outgoingPayment.setStatus(OutgoingPayment.Status.CONFIRMED);
							
							outgoingPaymentDao.updateOutgoingPayment(outgoingPayment);

							logDao.info("Outgoing Confirmation Payment", message.getTextContent());
							performOutgoingPaymentFraudCheck(message, outgoingPayment);
						} else {
							logDao.warn("Outgoing Confirmation Payment: No unconfirmed outgoing payment for the following confirmation message", message.getTextContent());
						}	
					}
				} catch (IllegalArgumentException ex) {
					ex.printStackTrace();
					logDao.error("Outgoing Confirmation Payment: Message failed to parse; likely incorrect format", message.getTextContent());
					throw new RuntimeException(ex);
				} catch (Exception ex) {
					logDao.error("Outgoing Confirmation Payment: Unexpected exception parsing outgoing payment SMS", message.getTextContent());
					throw new RuntimeException(ex);
				}
			}
		});
	}

	private void processOutgoingPayBillPayment(final FrontlineMessage message) {
		queueOutgoingJob(new PaymentJob() {
			public void run() {
				try {
					// Retrieve the corresponding outgoing payment with status
					// UNCONFIRMED
					List<OutgoingPayment> outgoingPayments;
					String payBillName = getPayBillName(message);
					String accountNo = getPayBillAccount(message);
					BigDecimal amount = getAmount(message);
					String specialValue = "PayBill:" + accountNo;
					
					outgoingPayments = outgoingPaymentDao.getOutgoingPaymentByFirstNameAndAmountAndSpecialAndStatus(
							payBillName, amount, specialValue,
							OutgoingPayment.Status.UNCONFIRMED);
					
					if (!outgoingPayments.isEmpty()) {
						final OutgoingPayment outgoingPayment = outgoingPayments.get(0);
						outgoingPayment.setConfirmationCode(getConfirmationCode(message));
						outgoingPayment.setTimeConfirmed(getTimePaid(message, true).getTime());
						outgoingPayment.setStatus(OutgoingPayment.Status.CONFIRMED);
						outgoingPaymentDao.updateOutgoingPayment(outgoingPayment);

						logDao.info("Outgoing Confirmation Payment", message.getTextContent());
						performOutgoingPaymentFraudCheck(message, outgoingPayment);
					} else {
						logDao.warn("Outgoing Confirmation Payment: No unconfirmed outgoing payment for the following confirmation message", message.getTextContent());
					}
				} catch (IllegalArgumentException ex) {
					ex.printStackTrace();
					logDao.error("Outgoing Confirmation Payment: Message failed to parse; likely incorrect format", message.getTextContent());
					throw new RuntimeException(ex);
				} catch (Exception ex) {
					logDao.error("Outgoing Confirmation Payment: Unexpected exception parsing outgoing payment SMS", message.getTextContent());
					throw new RuntimeException(ex);
				}
			}
		});
	}
	
	synchronized void performOutgoingPaymentFraudCheck(
			final FrontlineMessage message,
			final OutgoingPayment outgoingPayment) {
		BigDecimal tempBalanceAmount = getBalanceAmount();

		// check is: Let Previous Balance be p, Current Balance be c, Amount
		// sent be a, and MPesa transaction fees f
		// c == p - a - f
		// It might be wise that
		BigDecimal amountPaid = outgoingPayment.getAmountPaid();
		BigDecimal transactionFees = new BigDecimal(0);
		BigDecimal currentBalance = getBalance(message).setScale(2, BigDecimal.ROUND_HALF_DOWN);
		
		if (amountPaid.compareTo(new BigDecimal(100)) <= 0) {
			transactionFees = new BigDecimal(10);
		} else if (amountPaid.compareTo(new BigDecimal(35000)) <= 0) {
			transactionFees = new BigDecimal(30);
		} else {
			transactionFees = new BigDecimal(60);
		}

		BigDecimal expectedBalance = (tempBalanceAmount
			.subtract(outgoingPayment.getAmountPaid().add(transactionFees))).setScale(2, BigDecimal.ROUND_HALF_DOWN);

		updateBalance(currentBalance, outgoingPayment.getConfirmationCode(),
				new Date(outgoingPayment.getTimeConfirmed()), "Outgoing Payment");
		
		informUserOfFraudIfCommitted(expectedBalance, currentBalance,
				message.getTextContent());
	}

	private boolean isValidOutgoingPaymentConfirmation(FrontlineMessage message) {
		return message.getTextContent().matches(OUTGOING_PAYMENT_REGEX);
	}

	private boolean isInactivePayBillAccount(FrontlineMessage message) {
		return message.getTextContent().matches(OUTGOING_PAYMENT_INACTIVE_PAYBILL_REGEX);
	}
	//message.getTextContent().matches(OUTGOING_PAYMENT_TRANSACTION_FAILED)
	private boolean isValidOutgoingPaymentPayBillConfirmation(FrontlineMessage message) {
		return message.getTextContent().matches(OUTGOING_PAYMENT_PAYBILL_REGEX);
	}
	
	private boolean isValidOutgoingPaymentConfirmationForUnregisteredUser(FrontlineMessage message) {
		return message.getTextContent().matches(OUTGOING_PAYMENT_UNREGISTERED_USER_REGEX);
	}
//>END - OUTGOING PAYMENT REGION

	/*
	 * This function returns the active non-generic account, or the generic
	 * account if no accounts are active.
	 */
	@Override
	Account getAccount(FrontlineMessage message) {
		Client client = clientDao
				.getClientByPhoneNumber(getPhoneNumber(message));
		if (client != null) {
			List<Account> activeNonGenericAccountsByClientId = accountDao
					.getActiveNonGenericAccountsByClientId(client.getId());
			if (!activeNonGenericAccountsByClientId.isEmpty()) {
				return activeNonGenericAccountsByClientId.get(0);
			} else {			
				Account genericAccount = accountDao.getGenericAccountsByClientId(client.getId()); 
				if(genericAccount != null) {
					return genericAccount;
				} else {
					Account account = new Account(accountDao.createAccountNumber(), client, false, true);
					try {
						accountDao.saveAccount(account);
					} catch (DuplicateKeyException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					return accountDao.getGenericAccountsByClientId(client.getId());
				}
			}
		}
		return null;
	}
	
	@Override
	String getPaymentBy(FrontlineMessage message) {
		try {
			String nameAndPhone = getFirstMatch(message,
					"Ksh[,|.|\\d]+ from\n([A-Za-z ]+) 2547[0-9]{8}");
			String nameWKsh = nameAndPhone.split((AMOUNT_PATTERN + " from\n"))[1];
			String names = getFirstMatch(nameWKsh, PAID_BY_PATTERN).trim();
			return names;
		} catch (ArrayIndexOutOfBoundsException ex) {
			throw new IllegalArgumentException(ex);
		}
	}

	@Override
	Date getTimePaid(FrontlineMessage message) {
		return getTimePaid(message, false);
	}

	Date getTimePaid(FrontlineMessage message, boolean isOutgoingPayment) {
		String section1 = "";
		if (isOutgoingPayment) {
			section1 = message.getTextContent().split(" on ")[1];
		} else {
			section1 = message.getTextContent().split("\non ")[1];
		}
		String datetimesection = section1.split("New M-PESA balance")[0];
		String datetime = datetimesection.replace(" at ", " ");

		Date date = null;
		try {
			date = new SimpleDateFormat(DATETIME_PATTERN).parse(datetime);
		} catch (ParseException e) {
			e.printStackTrace();
		}
		return date;
	}
	
	boolean isSentToSameAccountMessage(FrontlineMessage message) {
		return message.getTextContent().matches(SENDING_PAYMENT_TO_SAME_ACCOUNT_REGEX);
	}

	boolean isInsufficientFundsMessage(FrontlineMessage message) {
		return message.getTextContent().matches(OUTGOING_PAYMENT_INSUFFICIENT_FUNDS_REGEX);
	}
	
	@Override
	boolean isMessageTextValid(String message) {
		return message.matches(INCOMING_PAYMENT_REGEX);
	}
	
	@Override
	public String toString() {
		return "M-PESA Kenya: Personal Service";
	}

	public PaymentServiceUiActionHandler getServiceActionUiHandler(UiGeneratorController ui) {
		return new MpesaPersonalActionUiHandler(this, ui);
	}
}
