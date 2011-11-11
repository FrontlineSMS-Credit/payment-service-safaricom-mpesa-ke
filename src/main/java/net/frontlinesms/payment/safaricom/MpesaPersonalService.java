package net.frontlinesms.payment.safaricom;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

import net.frontlinesms.data.DuplicateKeyException;
import net.frontlinesms.data.domain.FrontlineMessage;
import net.frontlinesms.payment.PaymentJob;
import net.frontlinesms.payment.PaymentServiceException;

import org.creditsms.plugins.paymentview.data.domain.Account;
import org.creditsms.plugins.paymentview.data.domain.Client;
import org.creditsms.plugins.paymentview.data.domain.LogMessage;
import org.creditsms.plugins.paymentview.data.domain.OutgoingPayment;
import org.smslib.CService;
import org.smslib.SMSLibDeviceException;
import org.smslib.handler.ATHandler.SynchronizedWorkflow;
import org.smslib.stk.StkConfirmationPrompt;
import org.smslib.stk.StkMenu;
import org.smslib.stk.StkResponse;
import org.smslib.stk.StkValuePrompt;

public class MpesaPersonalService extends MpesaPaymentService {

	// > REGEX PATTERN CONSTANTS
	private static final String STR_PERSONAL_INCOMING_PAYMENT_REGEX_PATTERN = "[A-Z0-9]+ Confirmed.\n"
			+ "You have received Ksh[,|.|\\d]+ from\n([A-Za-z ]+) 2547[\\d]{8}\non "
			+ "(([1-2]?[1-9]|[1-2]0|3[0-1])/([1-9]|1[0-2])/(1[0-2])) (at) ([1]?\\d:[0-5]\\d) (AM|PM)\n"
			+ "New M-PESA balance is Ksh[,|.|\\d]+";
	private static final Pattern PERSONAL_INCOMING_PAYMENT_REGEX_PATTERN = Pattern
			.compile(STR_PERSONAL_INCOMING_PAYMENT_REGEX_PATTERN);
	
	private static final String STR_SENDING_PAYMENT_TO_SAME_ACCOUNT_REGEX_PATTERN  = 
		"Failed. M-PESA cannot send Ksh([,|.|\\d]+) to 2547[\\d]{8}. " +
		"For more information call or SMS customer services on \\d{3}";
	private static final Pattern SENDING_PAYMENT_TO_SAME_ACCOUNT_REGEX_PATTERN = Pattern
	.compile(STR_SENDING_PAYMENT_TO_SAME_ACCOUNT_REGEX_PATTERN);
	//Failed. M-PESA cannot send Ksh67,100.00 to 254704593656. For more information call or SMS customer services on 234.
	
	private static final String STR_PERSONAL_OUTGOING_PAYMENT_INSUFFICIENT_FUNDS_REGEX_PATTERN  =
		"Failed. \nNot enough money in your M-PESA account to send Ksh[,|.|\\d]+.00. " +
		"You must be able to pay the transaction fee as well as the requested " +
		"amount.\nYour M-PESA balance is Ksh[,|.|\\d]+.00";
	
	private static final Pattern PERSONAL_OUTGOING_PAYMENT_INSUFFICIENT_FUNDS_REGEX_PATTERN = Pattern
	.compile(STR_PERSONAL_OUTGOING_PAYMENT_INSUFFICIENT_FUNDS_REGEX_PATTERN);
	
	private static final String STR_MPESA_PAYMENT_FAILURE_PATTERN = "";
	private static final String STR_LESS_THAN_MINIMUM_AMOUNT = "Failed. The amount is less than minimum M-PESA money transfer value.";
	private static final Pattern MPESA_PAYMENT_FAILURE_PATTERN = Pattern
			.compile(STR_MPESA_PAYMENT_FAILURE_PATTERN);

	private static final String STR_PERSONAL_OUTGOING_PAYMENT_REGEX_PATTERN = "[A-Z0-9]+ Confirmed. Ksh[,|.|\\d]+ "
		+ "sent to ([A-Za-z ]+) (\\+254[\\d]{9}|[\\d|-])+ "
		+ "on (([1-2]?[1-9]|[1-2]0|3[0-1])/([1-9]|1[0-2])/(1[0-2])) at ([1]?\\d:[0-5]\\d) ([A|P]M)(\\n| )"
		+ "New M-PESA balance is Ksh([,|.|\\d]+)";
	
	private static final Pattern PERSONAL_OUTGOING_PAYMENT_REGEX_PATTERN = Pattern
			.compile(STR_PERSONAL_OUTGOING_PAYMENT_REGEX_PATTERN);
	private static final String STR_BALANCE_REGEX_PATTERN = "[A-Z0-9]+ Confirmed.\n"
			+ "Your M-PESA balance was Ksh([,|.|\\d]+)\n"
			+ "on (([1-2]?[1-9]|[1-2]0|3[0-1])/([1-9]|1[0-2])/(1[0-2])) at (([1]?\\d:[0-5]\\d) ([A|P]M))";

	
	private static final Pattern BALANCE_REGEX_PATTERN = Pattern
			.compile(STR_BALANCE_REGEX_PATTERN);
	
	// > INSTANCE METHODS
	public void makePayment(final Client client,
			final OutgoingPayment outgoingPayment)
			throws PaymentServiceException {
		final CService cService = super.cService;
		final BigDecimal amount = outgoingPayment.getAmountPaid();
		queueRequestJob(new PaymentJob() {
			public void run() {
				try {
					cService.doSynchronized(new SynchronizedWorkflow<Object>() {
						public Object run() throws SMSLibDeviceException,
								IOException {

							initIfRequired();
							updateStatus(Status.SENDING);
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
									client.getPhoneNumber());
							try {							
								if (!(enterPhoneNumberResponse instanceof StkValuePrompt)) {
									logMessageDao.saveLogMessage(LogMessage.error(
											"Phone number rejected", ""));
									outgoingPayment.setStatus(OutgoingPayment.Status.ERROR);
	
										outgoingPaymentDao.updateOutgoingPayment(outgoingPayment);
										updateStatus(Status.ERROR);
									throw new RuntimeException(
											"Phone number rejected");
								}
								final StkResponse enterAmountResponse = cService
										.stkRequest(
												((StkValuePrompt) enterPhoneNumberResponse)
														.getRequest(), amount
														.toString());
								if (!(enterAmountResponse instanceof StkValuePrompt)) {
									logMessageDao.saveLogMessage(LogMessage.error("amount rejected", ""));
									outgoingPayment.setStatus(OutgoingPayment.Status.ERROR);
									outgoingPaymentDao.updateOutgoingPayment(outgoingPayment);
									updateStatus(Status.ERROR);
									throw new RuntimeException("amount rejected");
								}
								final StkResponse enterPinResponse = cService
										.stkRequest(((StkValuePrompt) enterAmountResponse)
														.getRequest(), pin);
								if (!(enterPinResponse instanceof StkConfirmationPrompt)) {
									logMessageDao.saveLogMessage(LogMessage.error(
											"PIN rejected", ""));
									outgoingPayment.setStatus(OutgoingPayment.Status.ERROR);
									outgoingPaymentDao.updateOutgoingPayment(outgoingPayment);
									updateStatus(Status.ERROR);
									throw new RuntimeException("PIN rejected");
								}
								final StkResponse confirmationResponse = cService
										.stkRequest(((StkConfirmationPrompt) enterPinResponse)
												.getRequest());
								if (confirmationResponse == StkResponse.ERROR) {
									logMessageDao.saveLogMessage(LogMessage.error(
											"Payment failed for some reason.", ""));
									outgoingPayment.setStatus(OutgoingPayment.Status.ERROR);
									outgoingPaymentDao.updateOutgoingPayment(outgoingPayment);
									updateStatus(Status.ERROR);
									throw new RuntimeException(
											"Payment failed for some reason.");
								}
								outgoingPayment.setStatus(OutgoingPayment.Status.UNCONFIRMED);
								logMessageDao.saveLogMessage(new LogMessage(
										LogMessage.LogLevel.INFO,
										"Outgoing Payment", outgoingPayment
												.toStringForLogs()));
	
								outgoingPaymentDao.updateOutgoingPayment(outgoingPayment);
								updateStatus(Status.COMPLETE);
							} catch (DuplicateKeyException e) {
								// TODO Auto-generated catch block
								updateStatus(Status.ERROR);
								e.printStackTrace();
							}
							return null;
						}
					});

				} catch (final SMSLibDeviceException ex) {
					logMessageDao.saveLogMessage(LogMessage.error(
							"SMSLibDeviceException in makePayment()",
							ex.getMessage()));
							outgoingPayment
							.setStatus(OutgoingPayment.Status.ERROR);
					try {
						outgoingPaymentDao.updateOutgoingPayment(outgoingPayment);
					} catch (DuplicateKeyException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				} catch (final IOException ex) {
					logMessageDao.saveLogMessage(LogMessage.error(
							"IOException in makePayment()", ex.getMessage()));
					outgoingPayment
					.setStatus(OutgoingPayment.Status.ERROR);
					try {
						outgoingPaymentDao.updateOutgoingPayment(outgoingPayment);
					} catch (DuplicateKeyException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			}
		});
	}
	
	@Override
	protected boolean isValidBalanceMessage(FrontlineMessage message) {
		return BALANCE_REGEX_PATTERN.matcher(message.getTextContent())
				.matches();
	}

	@Override
	protected void processMessage(final FrontlineMessage message) {
		if (message.getEndpointId() != null) {
			if(this.getSettings().getPsSmsModemSerial().equals(message.getEndpointId())) {
				if (isValidOutgoingPaymentConfirmation(message)) {
					processOutgoingPayment(message);
				} else if (isFailedMpesaPayment(message)) {
					logMessageDao.saveLogMessage(new LogMessage(LogMessage.LogLevel.ERROR,"Payment Message: Failed message",message.getTextContent()));
				} else if (isValidOutgoingPaymentConfirmation(message)) {
					processOutgoingPayment(message);
				} else if (isBellowMinimumAmount(message)) {
					reportBellowMinimumAmount();
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

	private void reportBellowMinimumAmount() {
		logMessageDao.saveLogMessage(LogMessage.error(
				"PAYMENT FAILED",
				"Payment Failed. Payment was less than minimum amount."));
	}
	
	private boolean isBellowMinimumAmount(final FrontlineMessage message) {
		if (!message.getSenderMsisdn().equals("MPESA")) {
			return false;
		}
		return message.getTextContent().contains(STR_LESS_THAN_MINIMUM_AMOUNT);
	}
	
	private boolean isFailedMpesaPayment(final FrontlineMessage message) {
		return MPESA_PAYMENT_FAILURE_PATTERN.matcher(message.getTextContent())
				.matches();
	}

	private void reportInsufficientFunds(final FrontlineMessage message) {
		queueResponseJob(new PaymentJob() {
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
						final OutgoingPayment outgoingPayment = outgoingPayments
								.get(0);
						outgoingPayment
								.setStatus(OutgoingPayment.Status.ERROR);

						outgoingPaymentDao
								.updateOutgoingPayment(outgoingPayment);
						
						logMessageDao.saveLogMessage(new LogMessage(
								LogMessage.LogLevel.ERROR,
								"PAYMENT FAILED", "Insufficient funds in mobile money account."));
					} else {
						logMessageDao
								.saveLogMessage(new LogMessage(
										LogMessage.LogLevel.WARNING,
										"Outgoing Confirmation Payment: No unconfirmed outgoing payment for the following confirmation message",
										message.getTextContent()));
					}
				} catch (IllegalArgumentException ex) {
					logMessageDao
							.saveLogMessage(new LogMessage(
									LogMessage.LogLevel.ERROR,
									"Outgoing Confirmation Payment: Message failed to parse; likely incorrect format",
									message.getTextContent()));
					throw new RuntimeException(ex);
				} catch (Exception ex) {
					logMessageDao
							.saveLogMessage(new LogMessage(
									LogMessage.LogLevel.ERROR,
									"Outgoing Confirmation Payment: Unexpected exception parsing outgoing payment SMS",
									message.getTextContent()));
					throw new RuntimeException(ex);
				}
			}
		});
	}
	
	private void reportSentToSameAccount(final FrontlineMessage message) {
		queueResponseJob(new PaymentJob() {
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
						final OutgoingPayment outgoingPayment = outgoingPayments
								.get(0);
						outgoingPayment.setTimeConfirmed(getTimePaid(message,
								true).getTime());
						outgoingPayment
								.setStatus(OutgoingPayment.Status.ERROR);

						outgoingPaymentDao
								.updateOutgoingPayment(outgoingPayment);
						
						logMessageDao.saveLogMessage(new LogMessage(
								LogMessage.LogLevel.ERROR,
								"PAYMENT FAILED", "Sending funds to the same mobile money account."));
					} else {
						logMessageDao
								.saveLogMessage(new LogMessage(
										LogMessage.LogLevel.WARNING,
										"Outgoing Confirmation Payment: No unconfirmed outgoing payment for the following confirmation message",
										message.getTextContent()));
					}
				} catch (IllegalArgumentException ex) {
					logMessageDao
							.saveLogMessage(new LogMessage(
									LogMessage.LogLevel.ERROR,
									"Outgoing Confirmation Payment: Message failed to parse; likely incorrect format",
									message.getTextContent()));
					throw new RuntimeException(ex);
				} catch (Exception ex) {
					logMessageDao
							.saveLogMessage(new LogMessage(
									LogMessage.LogLevel.ERROR,
									"Outgoing Confirmation Payment: Unexpected exception parsing outgoing payment SMS",
									message.getTextContent()));
					throw new RuntimeException(ex);
				}
			}
		});
	}
	
	private void processOutgoingPayment(final FrontlineMessage message) {
		queueResponseJob(new PaymentJob() {
			public void run() {
				try {
					// Retrieve the corresponding outgoing payment with status
					// UNCONFIRMED
					List<OutgoingPayment> outgoingPayments;
					if (getPhoneNumber(message).equals("")){
						//Paybill
						 outgoingPayments = outgoingPaymentDao
							.getByAmountPaidForInactiveClient(
									new BigDecimal(getAmount(message).toString()),
									OutgoingPayment.Status.UNCONFIRMED);
						 
					} else {
						 outgoingPayments = outgoingPaymentDao
							.getByPhoneNumberAndAmountPaid(
									getPhoneNumber(message), new BigDecimal(
											getAmount(message).toString()),
									OutgoingPayment.Status.UNCONFIRMED);
					}
					

					if (!outgoingPayments.isEmpty()) {
						final OutgoingPayment outgoingPayment = outgoingPayments
								.get(0);
						outgoingPayment
								.setConfirmationCode(getConfirmationCode(message));
						outgoingPayment.setTimeConfirmed(getTimePaid(message,
								true).getTime());
						outgoingPayment
								.setStatus(OutgoingPayment.Status.CONFIRMED);
						performOutgoingPaymentFraudCheck(message,
								outgoingPayment);

						outgoingPaymentDao
								.updateOutgoingPayment(outgoingPayment);

						logMessageDao.saveLogMessage(new LogMessage(
								LogMessage.LogLevel.INFO,
								"Outgoing Confirmation Payment", message
										.getTextContent()));
					} else {
						logMessageDao
								.saveLogMessage(new LogMessage(
										LogMessage.LogLevel.WARNING,
										"Outgoing Confirmation Payment: No unconfirmed outgoing payment for the following confirmation message",
										message.getTextContent()));
					}
				} catch (IllegalArgumentException ex) {
					logMessageDao
							.saveLogMessage(new LogMessage(
									LogMessage.LogLevel.ERROR,
									"Outgoing Confirmation Payment: Message failed to parse; likely incorrect format",
									message.getTextContent()));
					throw new RuntimeException(ex);
				} catch (Exception ex) {
					logMessageDao
							.saveLogMessage(new LogMessage(
									LogMessage.LogLevel.ERROR,
									"Outgoing Confirmation Payment: Unexpected exception parsing outgoing payment SMS",
									message.getTextContent()));
					throw new RuntimeException(ex);
				}
			}
		});
	}

	synchronized void performOutgoingPaymentFraudCheck(
			final FrontlineMessage message,
			final OutgoingPayment outgoingPayment) {
		BigDecimal tempBalanceAmount = balance.getBalanceAmount();

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

		informUserOfFraudIfCommitted(expectedBalance, currentBalance,
				message.getTextContent());

		balance.setBalanceAmount(currentBalance);
		balance.setConfirmationCode(outgoingPayment.getConfirmationCode());
		balance.setDateTime(new Date(outgoingPayment.getTimeConfirmed()));
		balance.setBalanceUpdateMethod("Outgoing Payment");

		balance.updateBalance();
	}

	private boolean isValidOutgoingPaymentConfirmation(FrontlineMessage message) {
		return PERSONAL_OUTGOING_PAYMENT_REGEX_PATTERN.matcher(
				message.getTextContent()).matches();
	}

	// >END - OUTGOING PAYMENT REGION

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
				return accountDao.getGenericAccountsByClientId(client.getId());
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
		return SENDING_PAYMENT_TO_SAME_ACCOUNT_REGEX_PATTERN.matcher(message.getTextContent()).matches();
	}

	boolean isInsufficientFundsMessage(FrontlineMessage message){
		return PERSONAL_OUTGOING_PAYMENT_INSUFFICIENT_FUNDS_REGEX_PATTERN.matcher(message.getTextContent()).matches();
	}
	
	@Override
	boolean isMessageTextValid(String message) {
		return PERSONAL_INCOMING_PAYMENT_REGEX_PATTERN.matcher(message).matches() 
			//|| PAYBILL_REGEX_PATTERN.matcher(message).matches()
			;
	}
	
	@Override
	public String toString() {
		return "M-PESA Kenya: Personal Service";
	}
}
