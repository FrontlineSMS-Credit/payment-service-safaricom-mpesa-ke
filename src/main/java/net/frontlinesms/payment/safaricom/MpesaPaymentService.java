package net.frontlinesms.payment.safaricom;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Calendar;
import java.util.Date;
import java.util.regex.Pattern;

import net.frontlinesms.data.domain.Contact;
import net.frontlinesms.data.domain.FrontlineMessage;
import net.frontlinesms.payment.PaymentJob;
import net.frontlinesms.payment.PaymentServiceException;

import org.creditsms.plugins.paymentview.analytics.TargetAnalytics;
import org.creditsms.plugins.paymentview.data.domain.Account;
import org.creditsms.plugins.paymentview.data.domain.Client;
import org.creditsms.plugins.paymentview.data.domain.IncomingPayment;
import org.creditsms.plugins.paymentview.data.domain.LogMessage;
import org.creditsms.plugins.paymentview.data.domain.OutgoingPayment;
import org.creditsms.plugins.paymentview.data.domain.Target;
import org.smslib.CService;
import org.smslib.SMSLibDeviceException;
import org.smslib.handler.ATHandler.SynchronizedWorkflow;
import org.smslib.stk.StkConfirmationPrompt;
import org.smslib.stk.StkMenu;
import org.smslib.stk.StkMenuItemNotFoundException;
import org.smslib.stk.StkRequest;
import org.smslib.stk.StkResponse;
import org.smslib.stk.StkValuePrompt;

public abstract class MpesaPaymentService extends AbstractPaymentService   {
//> REGEX PATTERN CONSTANTS
	protected static final String AMOUNT_PATTERN = "Ksh[,|.|\\d]+";
	protected static final String AMOUNT_PATTERN_WITHOUT_DOT = "Ksh[,\\d]+";
	protected static final String SENT_TO = " sent to";
	protected static final String DATETIME_PATTERN = "d/M/yy hh:mm a";
	protected static final String PHONE_PATTERN = "2547[\\d]{8}";
	protected static final String CONFIRMATION_CODE_PATTERN = "[A-Z0-9]+ Confirmed.";
	protected static final String PAID_BY_PATTERN = "([A-Za-z ]+)";
	protected static final String ACCOUNT_NUMBER_PATTERN = "Account Number [\\d]+";
	protected static final String RECEIVED_FROM = "received from";
	protected static final String WRONG_PIN_STR = "wrong PIN";
	
	private static final int BALANCE_ENQUIRY_CHARGE = 1;
	private static final BigDecimal BD_BALANCE_ENQUIRY_CHARGE = new BigDecimal(BALANCE_ENQUIRY_CHARGE);
	
	private static final String STR_REVERSE_REGEX_PATTERN = 
		"[A-Z0-9]+ Confirmed.\n"
		+"Transaction [A-Z0-9]+\n"
		+"has been reversed. Your\n"
		+"account balance now\n"
		+"[,|\\d]+Ksh";

	private static final Pattern REVERSE_REGEX_PATTERN = Pattern.compile(STR_REVERSE_REGEX_PATTERN);
	
//> INSTANCE PROPERTIES
	protected void processMessage(final FrontlineMessage message) {
		//I have overridden this function...
		if (isValidIncomingPaymentConfirmation(message)) {
			processIncomingPayment(message);
		}else if (isValidBalanceMessage(message)){
			processBalance(message);
		}else if (isValidReverseMessage(message)){
			processReversePayment(message);
		} else if (isInvalidPinMessage(message)) {
			logMessageDao.saveLogMessage(LogMessage.error(
					"PIN ERROR",
					"Action failed. You have entered an incorrect mobile money PIN. Please edit PIN and try again"));
		} else {
			logMessageDao.saveLogMessage(new LogMessage(LogMessage.LogLevel.INFO,"Payment Message",message.getTextContent()));
		}
	}
	
	public void sendAmountToPaybillAccount(final String businessName, final String businessNo, final String accountNo, final BigDecimal amount) {
		final CService cService = this.cService;
		queueRequestJob(new PaymentJob() {
			public void run() {
				try {
					cService.doSynchronized(new SynchronizedWorkflow<Object>() {
						public Object run() throws SMSLibDeviceException,
								IOException {

							initIfRequired();
							final StkMenu mPesaMenu = getMpesaMenu();
							final StkResponse payBillResponse = cService
								.stkRequest(mPesaMenu.getRequest("Pay Bill"));
							
							StkValuePrompt enterBusinessNumberPrompt;
							if (payBillResponse instanceof StkMenu) {
								enterBusinessNumberPrompt = (StkValuePrompt) cService
										.stkRequest(((StkMenu) payBillResponse)
												.getRequest("Enter business no."));
							} else {
								enterBusinessNumberPrompt = (StkValuePrompt) payBillResponse;
							}
							
							final StkResponse enterBusinessNumberResponse = cService.stkRequest(
									enterBusinessNumberPrompt.getRequest(), businessNo);
							
							final StkResponse enterAccountNumberPrompt;
							if (enterBusinessNumberResponse instanceof StkMenu) {
								enterAccountNumberPrompt = (StkValuePrompt) cService
										.stkRequest(((StkMenu) enterBusinessNumberResponse)
												.getRequest("Enter account no."));
							} else {
								enterAccountNumberPrompt = (StkValuePrompt) enterBusinessNumberResponse;
							}
							
							final StkResponse enterAccountNumberResponse = cService.stkRequest(
										((StkValuePrompt) enterAccountNumberPrompt)
												.getRequest(), accountNo
									);
							if (!(enterAccountNumberResponse instanceof StkValuePrompt)) {
								logMessageDao.saveLogMessage(LogMessage.error(
										"Account number rejected", ""));
								throw new RuntimeException("Account number rejected");
							}
							final StkResponse enterAmountResponse = cService
							.stkRequest(
									((StkValuePrompt) enterAccountNumberResponse)
											.getRequest(), amount
											.toString());
							if (!(enterAmountResponse instanceof StkValuePrompt)) {
								logMessageDao.saveLogMessage(LogMessage.error(
										"amount rejected", ""));
								throw new RuntimeException("amount rejected");
							}
								final StkResponse enterPinResponse = cService.stkRequest(
										((StkValuePrompt) enterAmountResponse).getRequest(), pin);
							if (!(enterPinResponse instanceof StkConfirmationPrompt)) {
								logMessageDao.saveLogMessage(LogMessage.error(
										"PIN rejected", ""));
								throw new RuntimeException("PIN rejected");
							}
							final StkResponse confirmationResponse = cService
									.stkRequest(((StkConfirmationPrompt) enterPinResponse)
											.getRequest());
							if (confirmationResponse == StkResponse.ERROR) {
								logMessageDao.saveLogMessage(LogMessage.error(
										"Payment failed for some reason.", ""));
								throw new RuntimeException(
										"Payment failed for some reason.");
							} else {
								//save outgoingpayment + create dummy client
								Client client;
								if (clientDao.getClientByPhoneNumber(businessNo) == null){
									client = new Client(businessName,"",businessNo);
									client.setActive(false);
									clientDao.saveClient(client);
								} else {
									client = clientDao.getClientByPhoneNumber(businessNo);
								}
								
							
								OutgoingPayment outgoingPayment = new OutgoingPayment();
								outgoingPayment.setClient(client);
								outgoingPayment.setAmountPaid(amount);
								outgoingPayment.setTimePaid(Calendar.getInstance().getTime());
								outgoingPayment.setNotes("");
								outgoingPayment.setStatus(OutgoingPayment.Status.UNCONFIRMED);
								outgoingPayment.setPaymentId("");
								outgoingPayment.setConfirmationCode("");
								outgoingPayment.setPaymentServiceSettings(MpesaPaymentService.this.getSettings());
								
								outgoingPaymentDao.saveOutgoingPayment(outgoingPayment);
								
								
							}
							return null;
						}
					});

				} catch (final SMSLibDeviceException ex) {
					logMessageDao.saveLogMessage(LogMessage.error(
							"SMSLibDeviceException in makePayment()",
							ex.getMessage()));
				} catch (final IOException ex) {
					logMessageDao.saveLogMessage(LogMessage.error(
							"IOException in makePayment()", ex.getMessage()));
				} catch (final Throwable ex) {
					logMessageDao.saveLogMessage(LogMessage.error(
							"Unexpected Exception in makePayment()", ex.getMessage()));
				}
			}
		});
	}

	public void checkBalance() throws PaymentServiceException {
		final String pin = this.pin;
		final CService cService = this.cService;
		queueRequestJob(new PaymentJob() {
			public void run() {
				try {
					cService.doSynchronized(new SynchronizedWorkflow<Object>() {
						public Object run() throws SMSLibDeviceException, IOException {
							initIfRequired();
							updateStatus(Status.CHECK_BALANCE);
							final StkMenu mPesaMenu = getMpesaMenu();
							final StkMenu myAccountMenu = (StkMenu) cService.stkRequest(mPesaMenu.getRequest("My account"));
							final StkResponse getBalanceResponse = cService.stkRequest(myAccountMenu.getRequest("Show balance"));
							
							final StkResponse enterPinResponse = cService.stkRequest(((StkValuePrompt) getBalanceResponse).getRequest(), pin);
							if(enterPinResponse == StkResponse.ERROR) throw new RuntimeException("PIN rejected");
							updateStatus(Status.CHECK_COMPLETE);
							BalanceDispatcher.getInstance().queuePaymentService(MpesaPaymentService.this);
							return null;
						}
					});
					// TODO check finalResponse is OK
					// TODO wait for response...
				} catch (final SMSLibDeviceException ex) {
					logMessageDao.saveLogMessage(LogMessage.error(
							"SMSLibDeviceException in checkBalance()",
							ex.getMessage()));
					updateStatus(Status.ERROR);
				} catch (final IOException e) {
					logMessageDao.saveLogMessage(LogMessage.error(
							"IOException in checkBalance()", e.getMessage()));
					updateStatus(Status.ERROR);
				} catch (RuntimeException e) {
					logMessageDao.saveLogMessage(LogMessage.error(
							"checkBalance failed for some reason.", e.getMessage()));
					updateStatus(Status.ERROR);
				}
			}
		});
	}

	private void processIncomingPayment(final FrontlineMessage message) {
		// TODO this method is ridiculously long
		queueResponseJob(new PaymentJob() {
			public void run() {
				try {
					final IncomingPayment payment = new IncomingPayment();
					// retrieve applicable account if the client exists
					Account account = getAccount(message);
					updateStatus(Status.RECEIVING);
					if (account != null){
						final Target tgt = targetDao.getActiveTargetByAccount(account.getAccountNumber());
						if (tgt != null){//account is a non generic one
							payment.setAccount(account);
							payment.setTarget(tgt);
							payment.setPhoneNumber(getPhoneNumber(message));
							payment.setAmountPaid(getAmount(message));
							payment.setConfirmationCode(getConfirmationCode(message));
							payment.setPaymentBy(getPaymentBy(message));
							payment.setTimePaid(getTimePaid(message));
							payment.setPaymentServiceSettings(MpesaPaymentService.this.getSettings());
							
							performIncominPaymentFraudCheck(message, payment);
							incomingPaymentDao.saveIncomingPayment(payment);

							// Check if the client has reached his targeted amount
							if (targetAnalytics.getStatus(tgt.getId()) == TargetAnalytics.Status.PAID){
								//Update target.completedDate
								final Calendar calendar = Calendar.getInstance();
								tgt.setCompletedDate(calendar.getTime());
								targetDao.updateTarget(tgt);
								// Update account.activeAccount
								payment.getAccount().setActiveAccount(false);
								accountDao.updateAccount(payment.getAccount());
							}
							updateStatus(Status.PROCESSED);
						} else {
							//account is a generic one(standard) or a non-generic without any active target(paybill)
							payment.setAccount(account);
							payment.setTarget(null);
							payment.setPhoneNumber(getPhoneNumber(message));
							payment.setAmountPaid(getAmount(message));
							payment.setConfirmationCode(getConfirmationCode(message));
							payment.setPaymentBy(getPaymentBy(message));
							payment.setTimePaid(getTimePaid(message));
							payment.setPaymentServiceSettings(MpesaPaymentService.this.getSettings());
							
							performIncominPaymentFraudCheck(message, payment);
							
							incomingPaymentDao.saveIncomingPayment(payment);
							updateStatus(Status.PROCESSED);
						}
					} else {
						// paybill - account does not exist (typing error) but client exists
						if (clientDao.getClientByPhoneNumber(getPhoneNumber(message))!=null){
							//save the incoming payment in generic account
							account = accountDao.getGenericAccountsByClientId(clientDao.getClientByPhoneNumber(getPhoneNumber(message)).getId());
							pvLog.warn("The account does not exist for this client. Incoming payment has been saved in generic account. "+ message.getTextContent());
						} else {
							// client does not exist in the database -> create client and generic account
							final String paymentBy = getPaymentBy(message);
							final String[] names = paymentBy.split(" ");
							String firstName = "";
							String otherName = "";
							if (names.length == 2){
							firstName = paymentBy.split(" ")[0];
							otherName = paymentBy.split(" ")[1];
							} else {
								otherName = paymentBy;
							}
							final Client client = new Client(firstName,otherName,getPhoneNumber(message));
							clientDao.saveClient(client);

							//Start Save the Client as a contact to the core project
							Contact contact = new Contact(client.getFullName(), client.getPhoneNumber(), "", "", "", true);
							contactDao.saveContact(contact);
							//Finish save
							
							account = new Account(createAccountNumber(),client,false,true);
							accountDao.saveAccount(account);
						}
						
						payment.setAccount(account);
						payment.setTarget(null);
						payment.setPhoneNumber(getPhoneNumber(message));
						payment.setAmountPaid(getAmount(message));
						payment.setConfirmationCode(getConfirmationCode(message));
						payment.setPaymentBy(getPaymentBy(message));
						payment.setTimePaid(getTimePaid(message));
						payment.setPaymentServiceSettings(MpesaPaymentService.this.getSettings());
						
						performIncominPaymentFraudCheck(message, payment);
						incomingPaymentDao.saveIncomingPayment(payment);
						updateStatus(Status.PROCESSED);
					}
					
					//log the saved incoming payment
					logMessageDao.saveLogMessage(
							new LogMessage(LogMessage.LogLevel.INFO,
										   	"Incoming Payment",
										   	message.getTextContent()));
				} catch (final IllegalArgumentException ex) {
					logMessageDao.saveLogMessage(
							new LogMessage(LogMessage.LogLevel.ERROR,
										   	"Incoming Payment: Message failed to parse; likely incorrect format",
										   	 message.getTextContent()));
					pvLog.warn("Message failed to parse; likely incorrect format", ex);
					updateStatus(Status.ERROR);
					throw new RuntimeException(ex);
				} catch (final Exception ex) {
					pvLog.error("Unexpected exception parsing incoming payment SMS.", ex);
					logMessageDao.saveLogMessage(
							new LogMessage(LogMessage.LogLevel.ERROR,
								   	"Incoming Payment: Unexpected exception parsing incoming payment SMS",
								   	message.getTextContent()));
					updateStatus(Status.ERROR);
					throw new RuntimeException(ex);
				}
			}
		});
	}
	
	private void processReversePayment(final FrontlineMessage message) {
		queueResponseJob(new PaymentJob() {
			public void run() {
				try {
					IncomingPayment incomingPayment = incomingPaymentDao.getByConfirmationCode(getReversedConfirmationCode(message));
					incomingPayment.setActive(false);
					
					performPaymentReversalFraudCheck(
						getConfirmationCode(message),
						incomingPayment.getAmountPaid(), 
						getReversedPaymentBalance(message),
						message
					);
					
					incomingPaymentDao.updateIncomingPayment(incomingPayment);
					logMessageDao.saveLogMessage(
							new LogMessage(LogMessage.LogLevel.INFO,"Reverse Transaction",message.getTextContent()));
				}catch (Exception e) {
					e.printStackTrace();
				}
			}
		});
	}
	
	protected void processBalance(final FrontlineMessage message){
		balanceDispatcher.notify(message);
	}
	
	void finaliseBalanceProcessing(final FrontlineMessage message) {
		queueResponseJob(new PaymentJob() {
			public void run() {
				performBalanceEnquiryFraudCheck(message);
				logMessageDao.saveLogMessage(
						new LogMessage(LogMessage.LogLevel.INFO,"Check Balance Response",message.getEndpointId() + ": " + message.getTextContent()));
			}
		});
	}
	
	private void performPaymentReversalFraudCheck(String confirmationCode, BigDecimal amountPaid, BigDecimal actualBalance, final FrontlineMessage message) {
		BigDecimal expectedBalance = balance.getBalanceAmount().subtract(amountPaid);
		
		informUserOfFraudIfCommitted(actualBalance, expectedBalance, message.getTextContent());
		
		balance.setBalanceAmount(actualBalance);
		balance.setBalanceUpdateMethod("PaymentReversal");
		balance.setDateTime(new Date());
		balance.setConfirmationCode(confirmationCode);
		
		balance.updateBalance();

	}

	private BigDecimal getReversedPaymentBalance(FrontlineMessage message) {
		String firstMatch = getFirstMatch(message, "[,|\\d]+Ksh");
		String strBalance = firstMatch.replace("Ksh", "").replace(",", "");
		return new BigDecimal(strBalance);
	}
	
	private String getReversedConfirmationCode(FrontlineMessage message) {
		String firstMatch = getFirstMatch(message, "Transaction [A-Z0-9]+");
		return firstMatch.replace("Transaction ", "").trim();
	}
	
	synchronized void performBalanceEnquiryFraudCheck(final FrontlineMessage message) {
		BigDecimal tempBalance = balance.getBalanceAmount();
		BigDecimal expectedBalance = tempBalance.subtract(BD_BALANCE_ENQUIRY_CHARGE);
		
		BigDecimal actualBalance = getAmount(message);
		informUserOfFraudIfCommitted(actualBalance, expectedBalance, message.getTextContent());
		
		balance.setBalanceAmount(actualBalance);
		balance.setConfirmationCode(getConfirmationCode(message));
		balance.setDateTime(getTimePaid(message));
		balance.setBalanceUpdateMethod("BalanceEnquiry");
		balance.updateBalance();
	}
	
	synchronized void performIncominPaymentFraudCheck(final FrontlineMessage message,
			final IncomingPayment payment) {
		//check is: Let Previous Balance be p, Current Balance be c and Amount received be a
		final BigDecimal actualBalance = getBalance(message);
		BigDecimal expectedBalance = payment.getAmountPaid().add(balance.getBalanceAmount());
		
		//c == p + a
		informUserOfFraudIfCommitted(actualBalance, expectedBalance, message.getTextContent());
		
		balance.setBalanceAmount(actualBalance);
		balance.setConfirmationCode(payment.getConfirmationCode());
		balance.setDateTime(new Date(payment.getTimePaid()));
		balance.setBalanceUpdateMethod("IncomingPayment");
		
		balance.updateBalance();
	}
	
	void informUserOfFraudIfCommitted(BigDecimal expectedBalance, BigDecimal actualBalance, String messageContent) {
		if(expectedBalance.compareTo(new BigDecimal(0)) < 0) {
			//Now we don't want Mathematical embarrassment... TODO explain
			pvLog.error("Balance for: "+ this.toString() +" is much lower than expected: " + actualBalance + " instead of: "+ expectedBalance);
		} else if(expectedBalance.equals(actualBalance)) {
			pvLog.info("No Fraud occured!");
		} else {
			String message = "Fraud commited on "+ this.toString() +"? Was expecting balance as: "+expectedBalance+", but was "+actualBalance;

			logMessageDao.saveLogMessage(
					new LogMessage(LogMessage.LogLevel.WARNING,
						   	message,
						    messageContent));
			pvLog.warn(message);
			this.eventBus.notifyObservers(new BalanceFraudNotification(message));
		}
	}
	
	private boolean isValidIncomingPaymentConfirmation(final FrontlineMessage message) {
		if (!message.getSenderMsisdn().equals("MPESA")) {
			return false;
		}
		return isMessageTextValid(message.getTextContent());
	}
	
	private boolean isInvalidPinMessage(final FrontlineMessage message) {
		if (!message.getSenderMsisdn().equals("MPESA")) {
			return false;
		}
		return message.getTextContent().contains(WRONG_PIN_STR);
	}
	
	private boolean isValidReverseMessage(FrontlineMessage message) {
		return REVERSE_REGEX_PATTERN.matcher(message.getTextContent()).matches();
	}
	
	BigDecimal getAmount(final FrontlineMessage message) {
		final String amountWithKsh = getFirstMatch(message, AMOUNT_PATTERN);
		String amountWithKshMinusZero = getFirstMatch(amountWithKsh, AMOUNT_PATTERN_WITHOUT_DOT);
		return new BigDecimal(amountWithKshMinusZero.substring(3).replaceAll(",", ""));
	}

	BigDecimal getBalance(final FrontlineMessage message) {
		try {
	        final String balance_part = getFirstMatch(message, "balance is Ksh[,|.|\\d]+");
	        String amountWithKsh = balance_part.split("balance is ")[1];
	        if (amountWithKsh.endsWith(".")){
	        	amountWithKsh = amountWithKsh.substring(0, amountWithKsh.length()-2);
	        }
	        return new BigDecimal(amountWithKsh.substring(3).replaceAll(",", ""));
		} catch(final ArrayIndexOutOfBoundsException ex) {
			throw new IllegalArgumentException(ex);
		}
	}	
	
	String getPhoneNumber(final FrontlineMessage message) {
		String str;
		try{
			 str = "+" + getFirstMatch(message, PHONE_PATTERN);
		}catch(IllegalStateException ex){
			str = "";
		}
		return str;
	}

	String getConfirmationCode(final FrontlineMessage message) {
		final String firstMatch = getFirstMatch(message, CONFIRMATION_CODE_PATTERN);
		return firstMatch.replace(" Confirmed.", "").trim();
	}

	StkMenu getMpesaMenu() throws StkMenuItemNotFoundException, SMSLibDeviceException, IOException {
		final StkResponse stkResponse = cService.stkRequest(StkRequest.GET_ROOT_MENU);
		StkMenu rootMenu = null;
		
		if (stkResponse instanceof StkMenu) {
			rootMenu = (StkMenu) stkResponse;
			return (StkMenu) cService.stkRequest(rootMenu.getRequest("M-PESA"));
		} else {
			throw new SMSLibDeviceException("StkResponse Error Returned.");
		}
	}

	/**
	 * 
	 * @return a generic account number
	 */
	public String createAccountNumber(){
		int accountNumberGenerated = this.accountDao.getAccountCount()+1;
		String accountNumberGeneratedStr = String.format("%05d", accountNumberGenerated);
		while (this.accountDao.getAccountByAccountNumber(accountNumberGeneratedStr) != null){
			accountNumberGeneratedStr = String.format("%05d", ++ accountNumberGenerated);
		}
		return accountNumberGeneratedStr;
	}
}
