package net.frontlinesms.plugins.payment.service.safaricomke;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.frontlinesms.FrontlineSMS;
import net.frontlinesms.data.DuplicateKeyException;
import net.frontlinesms.data.domain.FrontlineMessage;
import net.frontlinesms.data.domain.PersistableSettings;
import net.frontlinesms.data.events.EntitySavedNotification;
import net.frontlinesms.events.EventBus;
import net.frontlinesms.events.EventObserver;
import net.frontlinesms.events.FrontlineEventNotification;
import net.frontlinesms.junit.BaseTestCase;
import net.frontlinesms.messaging.sms.SmsServiceManager;
import net.frontlinesms.messaging.sms.modem.SmsModem;
import net.frontlinesms.plugins.payment.service.PaymentJob;
import net.frontlinesms.plugins.payment.service.PaymentServiceException;
import net.frontlinesms.plugins.payment.service.safaricomke.AbstractPaymentService;
import net.frontlinesms.plugins.payment.service.safaricomke.MpesaPaymentService;
import net.frontlinesms.serviceconfig.PasswordString;
import net.frontlinesms.ui.UiGeneratorController;

import org.apache.log4j.Logger;
import org.creditsms.plugins.paymentview.PaymentViewPluginController;
import org.creditsms.plugins.paymentview.analytics.TargetAnalytics;
import org.creditsms.plugins.paymentview.data.domain.Account;
import org.creditsms.plugins.paymentview.data.domain.Client;
import org.creditsms.plugins.paymentview.data.domain.IncomingPayment;
import org.creditsms.plugins.paymentview.data.domain.OutgoingPayment;
import org.creditsms.plugins.paymentview.data.domain.OutgoingPayment.Status;
import org.creditsms.plugins.paymentview.data.repository.AccountDao;
import org.creditsms.plugins.paymentview.data.repository.ClientDao;
import org.creditsms.plugins.paymentview.data.repository.IncomingPaymentDao;
import org.creditsms.plugins.paymentview.data.repository.LogMessageDao;
import org.creditsms.plugins.paymentview.data.repository.OutgoingPaymentDao;
import org.creditsms.plugins.paymentview.data.repository.PaymentServiceSettingsDao;
import org.creditsms.plugins.paymentview.data.repository.TargetDao;
import org.mockito.InOrder;
import org.smslib.CService;
import org.smslib.SMSLibDeviceException;
import org.smslib.handler.CATHandler_Wavecom_Stk;
import org.smslib.stk.StkConfirmationPrompt;
import org.smslib.stk.StkMenu;
import org.smslib.stk.StkMenuItem;
import org.smslib.stk.StkRequest;
import org.smslib.stk.StkValuePrompt;

/** Unit tests for {@link MpesaPaymentService} */
public abstract class MpesaPaymentServiceTest<E extends MpesaPaymentService> extends BaseTestCase {
	private static final String TEST_PIN = "1234";
	
	protected static final String PHONENUMBER_0 = "+254723908000";
	protected static final String PHONENUMBER_1 = "+254723908001";
	protected static final String PHONENUMBER_2 = "+254723908002";
	protected static final String PHONENUMBER_3 = "+254701035990";
	protected static final String ACCOUNTNUMBER_1_1 = "0700000011";
	protected static final String ACCOUNTNUMBER_2_1 = "0700000021";
	protected static final String ACCOUNTNUMBER_2_2 = "0700000022";
	protected static final String ACCOUNTNUMBER_2_3 = "12345";
	
	protected Client CLIENT_0;
	protected Client CLIENT_1;
	protected Client CLIENT_2;
	protected Client CLIENT_3;
	
	private CService cService;
	private CATHandler_Wavecom_Stk aTHandler;
	
	private StkMenuItem myAccountMenuItem;
	private StkRequest mpesaMenuItemRequest;
	private StkMenuItem sendMoneyMenuItem;
	
	private StkMenu mpesaMenu;
	private ClientDao clientDao;
	protected AccountDao accountDao;
	private TargetDao targetDao;
	private IncomingPaymentDao incomingPaymentDao;
	protected OutgoingPaymentDao outgoingPaymentDao;
	protected LogMessageDao logMessageDao;
	private UiGeneratorController ui;
	private TargetAnalytics targetAnalytics;
	protected E mpesaPaymentService;
	protected Logger logger;
	private PaymentViewPluginController pluginController;
	private EventBus eventBus;
	private PaymentServiceSettingsDao settingsDao;
	
	@Override
	protected void setUp() throws Exception {
		super.setUp();
		
		this.logger = mock(Logger.class);

		this.mpesaPaymentService = (E) createNewTestClass();
		this.mpesaPaymentService.setLog(logger);
		
		this.cService = SmsLibTestUtils.mockCService();
		this.aTHandler = mock(CATHandler_Wavecom_Stk.class);
		when(cService.getAtHandler()).thenReturn(aTHandler);
		when(cService.getAtHandler().supportsStk()).thenReturn(true);
		
		mpesaPaymentService.setSettings(mockSettings(
				AbstractPaymentService.PROPERTY_PIN, new PasswordString(TEST_PIN),
				AbstractPaymentService.PROPERTY_MODEM_SERIAL, "093SH5S655",
				AbstractPaymentService.PROPERTY_BALANCE_AMOUNT, new BigDecimal("200"),
				AbstractPaymentService.PROPERTY_BALANCE_UPDATE_METHOD, "balance enquiry",
				AbstractPaymentService.PROPERTY_BALANCE_CONFIRMATION_CODE, "7HHSK457S",
				AbstractPaymentService.PROPERTY_BALANCE_DATE_TIME, System.currentTimeMillis()));
		
		setUpDaos();

		StkMenuItem mpesaMenuItem = mockMenuItem("M-PESA", 129, 21);
		StkMenu rootMenu = new StkMenu("Safaricom", "Safaricom+", mpesaMenuItem);
		when((StkMenu)cService.stkRequest(StkRequest.GET_ROOT_MENU)).thenReturn(rootMenu);
		
		sendMoneyMenuItem = mockMenuItem("Send money");
		myAccountMenuItem = mockMenuItem("My account");
		mpesaMenuItemRequest = rootMenu.getRequest("M-PESA");
		mpesaMenu = new StkMenu("M-PESA",
				sendMoneyMenuItem , "Withdraw cash", "Buy airtime",
				"Pay Bill", "Buy Goods", "ATM Withdrawal", myAccountMenuItem);
		when(cService.stkRequest(mpesaMenuItemRequest)).thenReturn(mpesaMenu);
		SmsModem smsModem = mock(SmsModem.class);
		when(smsModem.getCService()).thenReturn(cService);
		mpesaPaymentService.setSmsModem(smsModem);
	}
	
	private PersistableSettings mockSettings(Object... settingsAndValues) {
		assert((settingsAndValues.length & 1) == 0): "Should be an equal number of setting keys and value";
		PersistableSettings s = new PersistableSettings(mpesaPaymentService);
		for(int i=0; i<settingsAndValues.length; i+=2) {
			String key = (String) settingsAndValues[i];
			Object value = settingsAndValues[i+1];
			s.set(key, value);
		}
		
		return s;
	}
	
	@Override
	protected void tearDown() throws Exception {
		super.tearDown();
		
		mpesaPaymentService.stopService();
	}

	protected abstract E createNewTestClass();

	abstract String[] getValidMessagesText();
	abstract String[] getInvalidMessagesText();
	
	public void testValidMessageText() {
		String[] validMessagesText = getValidMessagesText();
		for(int i=0; i<validMessagesText.length; i+=2) {
			String testDescription = validMessagesText[i];
			String messageText = validMessagesText[i+1];
			assertTrue(testDescription, mpesaPaymentService.isMessageTextValid(messageText));
		}
	}

	public void testInvalidMessageText() {
		String[] invalidMessagesText = getInvalidMessagesText();
		for(int i=0; i<invalidMessagesText.length; i+=2) {
			String testDescription = invalidMessagesText[i];
			String messageText = invalidMessagesText[i+1];
			assertFalse(testDescription, mpesaPaymentService.isMessageTextValid(messageText));
		}
	}

	@SuppressWarnings("unchecked")
	private void setUpDaos() throws Exception {
	
		incomingPaymentDao = mock(IncomingPaymentDao.class);
		outgoingPaymentDao= mock(OutgoingPaymentDao.class);
	    logMessageDao = mock(LogMessageDao.class);
		
		targetDao = mock(TargetDao.class);
		clientDao = mock(ClientDao.class);
		accountDao = mock(AccountDao.class);
		targetAnalytics = mock(TargetAnalytics.class);
		settingsDao = mock(PaymentServiceSettingsDao.class);
		
		when(targetAnalytics.getStatus(anyLong())).thenReturn(TargetAnalytics.Status.PAYING);
		
		pluginController = mock(PaymentViewPluginController.class);
		ui = mock(UiGeneratorController.class);
		
		FrontlineSMS fsms = mock(FrontlineSMS.class);
		SmsServiceManager smsSrvsManager = mock(SmsServiceManager.class);
		SmsModem smsModem = mock(SmsModem.class);
		
		List<SmsModem> smsModems = new ArrayList<SmsModem>();
		smsModems.add(smsModem);
		
		eventBus = mock(EventBus.class);
		when(fsms.getEventBus()).thenReturn(eventBus);
		when(ui.getFrontlineController()).thenReturn(fsms);
		when(ui.getFrontlineController().getSmsServiceManager()).thenReturn(smsSrvsManager);
		when(ui.getFrontlineController().getSmsServiceManager().getSmsModems()).thenReturn(smsModems);
		when(smsModem.getSerial()).thenReturn("093SH5S655");
		when(smsModem.isConnected()).thenReturn(true);
		
		cService = SmsLibTestUtils.mockCService();
		aTHandler = mock(CATHandler_Wavecom_Stk.class);
		when(smsModem.getCService()).thenReturn(cService);
		when(smsModem.getCService().getAtHandler()).thenReturn(aTHandler);
		when(smsModem.getCService().getAtHandler().supportsStk()).thenReturn(true);
		
		//Set Up Rules
		when(pluginController.getAccountDao()).thenReturn(accountDao);
		when(pluginController.getOutgoingPaymentDao()).thenReturn(outgoingPaymentDao);
		when(pluginController.getIncomingPaymentDao()).thenReturn(incomingPaymentDao);
		when(pluginController.getLogMessageDao()).thenReturn(logMessageDao);
		when(pluginController.getTargetDao()).thenReturn(targetDao);
		when(pluginController.getClientDao()).thenReturn(clientDao);
		when(pluginController.getUiGeneratorController()).thenReturn(ui);
		when(pluginController.getTargetAnalytics()).thenReturn(targetAnalytics);
		when(pluginController.getEventBus()).thenReturn(eventBus);
		when(pluginController.getPaymentServiceSettingsDao()).thenReturn(settingsDao);
		
		mpesaPaymentService.init(pluginController);
		
		IncomingPayment incomingPayment = new IncomingPayment();
		incomingPayment.setAmountPaid(new BigDecimal("1000"));
		incomingPayment.setConfirmationCode("BC77RI604");
		incomingPayment.setServiceSettings(mpesaPaymentService.getSettings());
		
		when(incomingPaymentDao.getByConfirmationCode("BC77RI604")).thenReturn(incomingPayment);
		
		//Set up accounts, targets and clients
		Set<Account> accounts1 = mockAccounts(ACCOUNTNUMBER_1_1);
		Set<Account> accounts2 = mockAccounts(ACCOUNTNUMBER_2_1, ACCOUNTNUMBER_2_2);
		Set<Account> accounts3 = mockAccounts(ACCOUNTNUMBER_2_3);
		
	    CLIENT_0 = mockClient(0, PHONENUMBER_0, Collections.EMPTY_SET);
	    CLIENT_1 = mockClient(1, PHONENUMBER_1, accounts1);
	    CLIENT_2 = mockClient(2, PHONENUMBER_2, accounts2);
	    CLIENT_3 = mockClient(3, PHONENUMBER_3, accounts3);

	}
	
	private Client mockClient(long id, String phoneNumber, Set<Account> accounts) {
		Client c = mock(Client.class);
		when(c.getId()).thenReturn(id);
		when(clientDao.getClientByPhoneNumber(phoneNumber)).thenReturn(c);
		when(accountDao.getAccountsByClientId(id)).thenReturn(new ArrayList<Account>(accounts));
		when(accountDao.getActiveNonGenericAccountsByClientId(id)).thenReturn(new ArrayList<Account>(accounts));
		when(c.getPhoneNumber()).thenReturn(phoneNumber);
		return c;
	}
	
	public void testCheckBalance() throws PaymentServiceException, SMSLibDeviceException, IOException  {
		// given
		StkRequest myAccountMenuItemRequest = mpesaMenu.getRequest("My account");
		StkMenuItem showBalanceMenuItem = mockMenuItem("Show balance");

		StkMenu myAccountMenu =	new StkMenu("My account", showBalanceMenuItem, "Call support",
				"Change PIN", "Secret word", "Language", "Update menu"); 
		when(cService.stkRequest(myAccountMenuItemRequest)).thenReturn(myAccountMenu);

		StkValuePrompt pinRequired = mockInputRequirement("Enter PIN");
		StkRequest pinRequiredRequest = pinRequired.getRequest();
		StkRequest showBalanceMenuItemRequest = myAccountMenu.getRequest("Show balance");
		when(cService.stkRequest(showBalanceMenuItemRequest)).thenReturn(pinRequired);
		
		// when
		mpesaPaymentService.checkBalance();
		
		waitForOutgoingJob();
		//Mock for StkRequest, hashCode: 18402106,Mock for StkRequest, hashCode: 18402106
		// then
		InOrder inOrder = inOrder(cService);
		inOrder.verify(cService).stkRequest(StkRequest.GET_ROOT_MENU);
		inOrder.verify(cService).stkRequest(mpesaMenuItemRequest);
		inOrder.verify(cService).stkRequest(myAccountMenuItemRequest);
		inOrder.verify(cService).stkRequest(showBalanceMenuItemRequest);
		inOrder.verify(cService).stkRequest(pinRequiredRequest, TEST_PIN);
	}
	
	public void testMakePayment() throws PaymentServiceException, SMSLibDeviceException, IOException  {
		// given
		StkValuePrompt phoneNumberRequired = mockInputRequirement("Enter phone no.");
		when(cService.stkRequest(sendMoneyMenuItem)).thenReturn(phoneNumberRequired);
		
		StkRequest phoneNumberRequest = phoneNumberRequired.getRequest();
		StkValuePrompt amountRequired = mockInputRequirement("Enter amount");
		when(cService.stkRequest(phoneNumberRequest, PHONENUMBER_1)).thenReturn(amountRequired);
		
		StkRequest amountRequest = amountRequired.getRequest();
		StkValuePrompt pinRequired = mockInputRequirement("Enter PIN");
		when(cService.stkRequest(amountRequest, "500")).thenReturn(pinRequired);
		
		StkRequest pinRequiredRequest = pinRequired.getRequest();
		StkConfirmationPrompt pinRequiredResponse = mockConfirmation("Send money to "+CLIENT_1.getPhoneNumber()+" Ksh500");
		when(cService.stkRequest(pinRequiredRequest, TEST_PIN)).thenReturn(pinRequiredResponse);
		
		// when
		mpesaPaymentService.makePayment(getOutgoingPayment(CLIENT_1));
		
		waitForOutgoingJob();
		
		// then
		InOrder inOrder = inOrder(cService);
		inOrder.verify(cService).stkRequest(StkRequest.GET_ROOT_MENU);
		inOrder.verify(cService).stkRequest(mpesaMenuItemRequest);
		inOrder.verify(cService).stkRequest(sendMoneyMenuItem);
		inOrder.verify(cService).stkRequest(phoneNumberRequest , PHONENUMBER_1);
		inOrder.verify(cService).stkRequest(amountRequest , "500");
		inOrder.verify(cService).stkRequest(pinRequiredRequest , TEST_PIN);
	}
	

	
	public void testPaymentReversalProcessing(){
		paymentReversalProcessing(
				"DXAH67GH9 Confirmed.\n"
						+"Transaction BC77RI604\n"
						+"has been reversed. Your\n"
						+"account balance now\n"
						+"0Ksh",
				"DXAH67GH9","BC77RI604");
	}
	
	protected void paymentReversalProcessing(String messageText,
			final String confirmationCode, final String reversedConfirmationCode) {
		// then
		assertTrue(mpesaPaymentService instanceof EventObserver);
		
		// when
		notifyAndWait_incoming(mockMessageNotification("MPESA", messageText));
		
		// then
		verify(incomingPaymentDao).getByConfirmationCode(reversedConfirmationCode);
		verify(incomingPaymentDao).updateIncomingPayment(new IncomingPayment() {
			@Override
			public boolean equals(Object that) {
				if(!(that instanceof IncomingPayment)) return false;
				IncomingPayment other = (IncomingPayment) that;
				return other.getConfirmationCode().equals(reversedConfirmationCode);
			}
		});
		
	}
	
	public void testSendAmountToPaybillAccount() throws PaymentServiceException, SMSLibDeviceException, IOException  {
		// given
		StkRequest paybillMenuItemRequest = mpesaMenu.getRequest("Pay Bill");

		StkValuePrompt businessNumberRequired = mockInputRequirement("Enter business no.");
		when(cService.stkRequest(paybillMenuItemRequest)).thenReturn(businessNumberRequired);
		
		StkRequest businessNumberRequest = businessNumberRequired.getRequest();
		StkValuePrompt accountRequired = mockInputRequirement("Enter account no.");
		when(cService.stkRequest(businessNumberRequest, "320320")).thenReturn(accountRequired);
		
		StkRequest accountRequest = accountRequired.getRequest();
		StkValuePrompt amountRequired = mockInputRequirement("Enter amount");
		when(cService.stkRequest(accountRequest, "68949")).thenReturn(amountRequired);
		
		StkRequest amountRequest = accountRequired.getRequest();
		StkValuePrompt pinRequired = mockInputRequirement("Enter PIN");
		when(cService.stkRequest(amountRequest, "500")).thenReturn(pinRequired);
		
		StkRequest pinRequiredRequest = pinRequired.getRequest();
		StkConfirmationPrompt pinRequiredResponse = mockConfirmation("Send money to ZUKU Ksh500");
		when(cService.stkRequest(pinRequiredRequest, TEST_PIN)).thenReturn(pinRequiredResponse);
		
		// when
		mpesaPaymentService.sendAmountToPaybillAccount("ZUKU","320320","68949", new BigDecimal(100));
		
		waitForOutgoingJob();
		
		// then
		InOrder inOrder = inOrder(cService);
		inOrder.verify(cService).stkRequest(StkRequest.GET_ROOT_MENU);
		inOrder.verify(cService).stkRequest(paybillMenuItemRequest);
		inOrder.verify(cService).stkRequest(businessNumberRequest, "320320");
		inOrder.verify(cService).stkRequest(accountRequest, "68949");
	}

	protected void testIncomingPaymentProcessing(String messageText,
			final String phoneNo, final String accountNumber, final String amount,
			final String confirmationCode, final String payedBy, final String datetime) {
		// then
		assertTrue(mpesaPaymentService instanceof EventObserver);
		
		// when
		notifyAndWait_incoming(mockMessageNotification("MPESA", messageText));
		
		// then
		IncomingPayment ip = new IncomingPayment() {
			@Override
			public boolean equals(Object that) {
				
				if(!(that instanceof IncomingPayment)) return false;
				IncomingPayment other = (IncomingPayment) that;
				
				return other.getPhoneNumber().equals(phoneNo) &&
					other.getAmountPaid().equals(new BigDecimal(amount)) &&
					other.getAccount().getAccountNumber().equals(accountNumber) &&
					other.getConfirmationCode().equals(confirmationCode) &&
					other.getTimePaid().equals(getTimestamp(datetime).getTime()) &&
					other.getPaymentBy().equals(payedBy);
			}
		};
		ip.setAccount(accountDao.getAccountByAccountNumber(accountNumber));
		ip.setPhoneNumber(phoneNo);
		ip.setAmountPaid(new BigDecimal(amount));
		ip.setConfirmationCode(confirmationCode);
		ip.setTimePaid(getTimestamp(datetime));
		ip.setPaymentBy(payedBy);
		
		verify(incomingPaymentDao).saveIncomingPayment(ip);
	}
	
	protected void testOutgoingPaymentProcessing(String messageText,
			final String phoneNo, final String accountNumber, final String amount,
			final String confirmationCode, final String payTo, final String datetime, 
			final OutgoingPayment.Status status) throws DuplicateKeyException {
		// setup
		assertTrue(mpesaPaymentService instanceof EventObserver);
		OutgoingPayment payment = new OutgoingPayment();
		payment.setClient(CLIENT_1);
		payment.setAmountPaid(new BigDecimal(amount));
		
		payment.setConfirmationCode(confirmationCode);
		payment.setTimeConfirmed(getTimestamp(datetime).getTime());
		payment.setStatus(status);
		payment.setPaymentServiceSettings(mpesaPaymentService.getSettings());
		
		when(outgoingPaymentDao.getByPhoneNumberAndAmountPaid(phoneNo, new BigDecimal(amount), status)).
				thenReturn(Arrays.asList(payment));
		
		// when
		notifyAndWait_outgoing(mockMessageNotification("MPESA", messageText));
		
		// then
		verify(outgoingPaymentDao).updateOutgoingPayment(payment);
	}
	
	protected void testBalanceProcessing(String messageText, String amount,
			String confirmationMessage, String timestamp) {
		FrontlineMessage message = mockMessage("MPESA", messageText);
		
		assertTrue(mpesaPaymentService.isValidBalanceMessage(message));
		
		notifyAndWait_incoming(new EntitySavedNotification<FrontlineMessage>(message));
		
		assertEquals(new BigDecimal(amount), mpesaPaymentService.getBalanceAmount());
		assertEquals(confirmationMessage, mpesaPaymentService.getBalanceConfirmationCode());
		assertEquals(getTimestamp(timestamp), mpesaPaymentService.getBalanceDateTime());
	}
	
	private Date getTimestamp(String dateString) {
		try {
			return new SimpleDateFormat("d/M/yy hh:mm a").parse(dateString);
		} catch (ParseException e) {
			throw new IllegalArgumentException("Test date supplied in incorrect format: " + dateString);
		}
	}

	public void testIncomingPaymentProcessorIgnoresIrrelevantMessages() {
		// when
		testIncomingPaymentProcessorIgnoresMessage("MPESA", "Some random text...");
		testIncomingPaymentProcessorIgnoresMessage("0798765432", "... and some more random text.");
	}
	
	protected void testIncomingPaymentProcessorIgnoresMessage(String fromNumber, String messageText) {
		notifyAndWait_incoming(mockMessageNotification(fromNumber, messageText));
		
		// then
		verify(incomingPaymentDao, never()).saveIncomingPayment(any(IncomingPayment.class));
	}
	
	public void testFakedIncomingPayment() {
		// Message text which looks reasonable, but is wrong format
		testFakedIncomingPayment("0798765432", "0712345678 sent payment of 500 KES");
		
		// Genuine MPESA message text, with bad number
		testFakedIncomingPayment("0798765432", "BI94HR849 Confirmed.\n" +
				"You have received Ksh1,235 JOHN KIU 254723908653 on 3/5/11 at 10:35 PM\n" +
				"New M-PESA balance Ksh1,236");
		
		// Genuine PayBill message text, with bad number
		testFakedIncomingPayment("0798765432", "BH45UU225 Confirmed.\n" +
				"on 5/4/11 at 2:45 PM\n" +
				"Ksh950 received from ELLY ASAKHULU 254713698227.\n" +
				"Account Number 0713698227\n" +
				"New Utility balance is Ksh50,802\n" +
				"Time: 05/04/2011 14:45:34");
		
		// Genuine PayBill message from correct number, but with bad date (29 Undecimber)
		testFakedIncomingPayment("MPESA", "BHT57U225XXX Confirmed.\n"
				+ "on 29/13/11 at 1:45 PM\n"
				+ "Ksh123 received from ELLY 254723908002.\n"
				+ "Account Number 0700000022\n"
				+ "New Utility balance is Ksh50,802\n"
				+ "Time: 29/13/2011 16:45:34");
	}
	
	private void testFakedIncomingPayment(String from, String messageText) {
		// when
		notifyAndWait_incoming(mockMessageNotification(from, messageText));
		
		// then
		verify(incomingPaymentDao, never()).saveIncomingPayment(any(IncomingPayment.class));
	}
	
	private void notifyAndWait_incoming(FrontlineEventNotification notification) {
		mpesaPaymentService.notify(notification);
		waitForIncomingJob();
	}
	
	private void notifyAndWait_outgoing(FrontlineEventNotification notification) {
		mpesaPaymentService.notify(notification);
		waitForOutgoingJob();
	}
	
	private StkMenuItem mockMenuItem(String title, int... numbers) {
		StkMenuItem i = mock(StkMenuItem.class);
		when(i.getText()).thenReturn(title);
		StkRequest mockRequest = mock(StkRequest.class);
		when(i.getRequest()).thenReturn(mockRequest);
		return i;
	}
	
	private StkValuePrompt mockInputRequirement(String title, int... nums) {
		StkValuePrompt ir = mock(StkValuePrompt.class);
		when(ir.getText()).thenReturn(title);
		
		StkRequest mockRequest = mock(StkRequest.class);
		when(ir.getRequest()).thenReturn(mockRequest);
		return ir;
	}
	
	private StkConfirmationPrompt mockConfirmation(String title) {
		StkConfirmationPrompt ir = mock(StkConfirmationPrompt.class);
		when(ir.getText()).thenReturn(title);
		
		StkRequest mockRequest = mock(StkRequest.class);
		when(ir.getRequest()).thenReturn(mockRequest);
		return ir;
	}
	
	private EntitySavedNotification<FrontlineMessage> mockMessageNotification(String from, String text) {
		return new EntitySavedNotification<FrontlineMessage>(mockMessage(from, text));
	}
	
	private FrontlineMessage mockMessage(String from, String text) {
		FrontlineMessage m = mock(FrontlineMessage.class);
		when(m.getSenderMsisdn()).thenReturn(from);
		when(m.getTextContent()).thenReturn(text);
		when(m.getEndpointId()).thenReturn("@093SH5S655");
		return m;
	}
	
	private OutgoingPayment getOutgoingPayment(Client client){
		OutgoingPayment outgoingPayment = new OutgoingPayment();
		outgoingPayment.setClient(client);
		outgoingPayment.setAmountPaid(new BigDecimal("500"));
		outgoingPayment.getStatus();
		outgoingPayment.setStatus(Status.CREATED);
		outgoingPayment.setTimePaid(Calendar.getInstance().getTime());
		return outgoingPayment;
	}
	
	private Set<Account> mockAccounts(String... accountNumbers) {
		ArrayList<Account> accounts = new ArrayList<Account>();
		for(String accountNumber : accountNumbers) {
			Account account = mock(Account.class);
			when(account.getAccountNumber()).thenReturn(accountNumber);
			accounts.add(account);
			when(accountDao.getAccountByAccountNumber(accountNumber)).thenReturn(account);
		}
		return new HashSet<Account>(accounts);
	}

	private void waitForIncomingJob() {
		WaitingJob.waitForEvent(mpesaPaymentService, true);
	}

	private void waitForOutgoingJob() {
		WaitingJob.waitForEvent(mpesaPaymentService, false);
	}
}

class WaitingJob implements PaymentJob {
	private final MpesaPaymentService s;
	private boolean running;
	
	private WaitingJob(MpesaPaymentService s) {
		assert s != null: "Please set a payment service.";
		this.s = s;
	}
	
	private void block(boolean incomingJob) {
		running = true;
		if(incomingJob) s.queueIncomingJob(this);
		else s.queueOutgoingJob(this);
		while(running) {
			try {
				Thread.sleep(200);
			} catch (InterruptedException e) {
				running = false;
			}
		}
	}
	
	public void run() {
		running = false;
	}
	
	/** Put a job on the UI event queue, and block until it has been run. */
	public static void waitForEvent(MpesaPaymentService s, boolean incomingJob) {
		new WaitingJob(s).block(incomingJob);
	}
}