package net.frontlinesms.payment.safaricom;

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
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import net.frontlinesms.FrontlineSMS;
import net.frontlinesms.data.DuplicateKeyException;
import net.frontlinesms.data.domain.FrontlineMessage;
import net.frontlinesms.data.events.EntitySavedNotification;
import net.frontlinesms.events.EventBus;
import net.frontlinesms.events.EventObserver;
import net.frontlinesms.junit.BaseTestCase;
import net.frontlinesms.payment.PaymentServiceException;
import net.frontlinesms.test.smslib.SmsLibTestUtils;
import net.frontlinesms.ui.UiGeneratorController;
import net.frontlinesms.ui.events.FrontlineUiUpateJob;

import org.apache.log4j.Logger;
import org.creditsms.plugins.paymentview.PaymentViewPluginController;
import org.creditsms.plugins.paymentview.analytics.TargetAnalytics;
import org.creditsms.plugins.paymentview.data.domain.Account;
import org.creditsms.plugins.paymentview.data.domain.Client;
import org.creditsms.plugins.paymentview.data.domain.IncomingPayment;
import org.creditsms.plugins.paymentview.data.domain.OutgoingPayment;
import org.creditsms.plugins.paymentview.data.domain.OutgoingPayment.Status;
import org.creditsms.plugins.paymentview.data.domain.PaymentServiceSettings;
import org.creditsms.plugins.paymentview.data.repository.AccountDao;
import org.creditsms.plugins.paymentview.data.repository.ClientDao;
import org.creditsms.plugins.paymentview.data.repository.IncomingPaymentDao;
import org.creditsms.plugins.paymentview.data.repository.LogMessageDao;
import org.creditsms.plugins.paymentview.data.repository.OutgoingPaymentDao;
import org.creditsms.plugins.paymentview.data.repository.TargetDao;
import org.creditsms.plugins.paymentview.userhomepropeties.payment.balance.Balance;
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
	protected static final String PHONENUMBER_0 = "+254723908000";
	protected static final String PHONENUMBER_1 = "+254723908001";
	protected static final String PHONENUMBER_2 = "+254723908002";
	protected static final String ACCOUNTNUMBER_1_1 = "0700000011";
	protected static final String ACCOUNTNUMBER_2_1 = "0700000021";
	protected static final String ACCOUNTNUMBER_2_2 = "0700000022";
	
	protected Client CLIENT_0;
	protected Client CLIENT_1;
	protected Client CLIENT_2;
	
	private CService cService;
	private CATHandler_Wavecom_Stk aTHandler;
	protected Balance balance;
	
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
	protected BalanceDispatcher balanceDispatcher;
	
	@Override
	protected void setUp() throws Exception {
		super.setUp();

		this.mpesaPaymentService = (E) createNewTestClass();
		
		this.cService = SmsLibTestUtils.mockCService();
		this.aTHandler = mock(CATHandler_Wavecom_Stk.class);
		when(cService.getAtHandler()).thenReturn(aTHandler);
		mpesaPaymentService.setCService(cService);
		
		PaymentServiceSettings paymentServiceSettings = mock(PaymentServiceSettings.class);
		when(paymentServiceSettings.getPsSmsModemSerial()).thenReturn("093SH5S655");
		mpesaPaymentService.setSettings(paymentServiceSettings);
		
		this.balance = new Balance();
		balance.setBalanceAmount(new BigDecimal("200"));
		balance.setBalanceUpdateMethod("balance enquiry");
		balance.setConfirmationCode("7HHSK457S");
		balance.setDateTime(new Date());
		balance.setEventBus(mock(EventBus.class));
		balance.setPaymentService(mpesaPaymentService);
		mpesaPaymentService.setBalance(balance);
		
		this.balanceDispatcher = BalanceDispatcher.getInstance();

		
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
		
		init();
	}
	
	@Override
	protected void tearDown() throws Exception {
		super.tearDown();
		
		mpesaPaymentService.stop();
		
		deinit();
	}

	protected void init(){}
	protected void deinit(){
		balance.reset();
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
	private void setUpDaos() {
		incomingPaymentDao = mock(IncomingPaymentDao.class);
		outgoingPaymentDao= mock(OutgoingPaymentDao.class);
	    logMessageDao = mock(LogMessageDao.class);
		
		targetDao = mock(TargetDao.class);
		clientDao = mock(ClientDao.class);
		accountDao = mock(AccountDao.class);
		targetAnalytics = mock(TargetAnalytics.class);
		when(targetAnalytics.getStatus(anyLong())).thenReturn(TargetAnalytics.Status.PAYING);
		
		pluginController = mock(PaymentViewPluginController.class);
		ui = mock(UiGeneratorController.class);
		
		FrontlineSMS fsms = mock(FrontlineSMS.class);

		EventBus eventBus = mock(EventBus.class);
		mpesaPaymentService.registerToEventBus(eventBus);
		when(fsms.getEventBus()).thenReturn(eventBus);
		when(ui.getFrontlineController()).thenReturn(fsms);
		
		//Set Up Rules
		when(pluginController.getAccountDao()).thenReturn(accountDao);
		when(pluginController.getOutgoingPaymentDao()).thenReturn(outgoingPaymentDao);
		when(pluginController.getIncomingPaymentDao()).thenReturn(incomingPaymentDao);
		when(pluginController.getLogMessageDao()).thenReturn(logMessageDao);
		when(pluginController.getTargetDao()).thenReturn(targetDao);
		when(pluginController.getClientDao()).thenReturn(clientDao);
		when(pluginController.getUiGeneratorController()).thenReturn(ui);
		when(pluginController.getTargetAnalytics()).thenReturn(targetAnalytics);
		
		logger = mock(Logger.class);
		when(pluginController.getLogger(any(Class.class))).thenReturn(logger);
		
		mpesaPaymentService.initDaosAndServices(pluginController);
		
		IncomingPayment incomingPayment = new IncomingPayment();
		incomingPayment.setAmountPaid(new BigDecimal("1000"));
		incomingPayment.setConfirmationCode("BC77RI604");
		incomingPayment.setPaymentServiceSettings(mpesaPaymentService.getSettings());
		
		when(incomingPaymentDao.getByConfirmationCode("BC77RI604")).thenReturn(incomingPayment);
		
		//Set up accounts, targets and clients
		Set<Account> accounts1 = mockAccounts(ACCOUNTNUMBER_1_1);
		Set<Account> accounts2 = mockAccounts(ACCOUNTNUMBER_2_1, ACCOUNTNUMBER_2_2);
		
	    CLIENT_0 = mockClient(0, PHONENUMBER_0, Collections.EMPTY_SET);
	    CLIENT_1 = mockClient(1, PHONENUMBER_1, accounts1);
	    CLIENT_2 = mockClient(2, PHONENUMBER_2, accounts2);


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
		// setup
		StkRequest myAccountMenuItemRequest = mpesaMenu.getRequest("My account");
		
		StkMenuItem showBalanceMenuItem = mockMenuItem("Show balance");

		StkMenu myAccountMenu =	new StkMenu("My account", showBalanceMenuItem, "Call support",
				"Change PIN", "Secret word", "Language", "Update menu"); 
		when(cService.stkRequest(myAccountMenuItemRequest)).thenReturn(myAccountMenu);

		StkValuePrompt pinRequired = mockInputRequirement("Enter PIN");
		StkRequest pinRequiredRequest = pinRequired.getRequest();
		StkRequest showBalanceMenuItemRequest = myAccountMenu.getRequest("Show balance");
		when(cService.stkRequest(showBalanceMenuItemRequest)).thenReturn(pinRequired);
		
		// given
		mpesaPaymentService.setPin("1234");
		
		mpesaPaymentService.checkBalance();
		
		WaitingJob.waitForEvent(500);
		
		// then
		InOrder inOrder = inOrder(cService);
		inOrder.verify(cService).stkRequest(StkRequest.GET_ROOT_MENU);
		inOrder.verify(cService).stkRequest(mpesaMenuItemRequest);
		inOrder.verify(cService).stkRequest(myAccountMenuItemRequest);
		inOrder.verify(cService).stkRequest(showBalanceMenuItemRequest);
		
		inOrder.verify(cService).stkRequest(pinRequiredRequest, "1234");
	}
	
	public void testMakePayment() throws PaymentServiceException, SMSLibDeviceException, IOException  {
		// setup
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
		when(cService.stkRequest(pinRequiredRequest, "1234")).thenReturn(pinRequiredResponse);
		
		// given
		mpesaPaymentService.setPin("1234");
		
		// when
		mpesaPaymentService.makePayment(CLIENT_1, getOutgoingPayment(CLIENT_1));
		
		WaitingJob.waitForEvent(3500);
		
		// then
		InOrder inOrder = inOrder(cService);
		inOrder.verify(cService).stkRequest(StkRequest.GET_ROOT_MENU);
		inOrder.verify(cService).stkRequest(mpesaMenuItemRequest);
		inOrder.verify(cService).stkRequest(sendMoneyMenuItem);
		inOrder.verify(cService).stkRequest(phoneNumberRequest , PHONENUMBER_1);
		inOrder.verify(cService).stkRequest(amountRequest , "500");
		inOrder.verify(cService).stkRequest(pinRequiredRequest , "1234");
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
		mpesaPaymentService.notify(mockMessageNotification("MPESA", messageText));
		
		// then
		WaitingJob.waitForEvent(1000);
		
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
		// setup
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
		when(cService.stkRequest(pinRequiredRequest, "1234")).thenReturn(pinRequiredResponse);
		
		// given
		mpesaPaymentService.setPin("1234");
		
		// when
		mpesaPaymentService.sendAmountToPaybillAccount("ZUKU","320320","68949", new BigDecimal(100));
		
		WaitingJob.waitForEvent(3000);
		
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
		mpesaPaymentService.notify(mockMessageNotification("MPESA", messageText));
		
		WaitingJob.waitForEvent(2000);
		verify(incomingPaymentDao).saveIncomingPayment(new IncomingPayment() {
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
		});
	}
	
	protected void testOutgoingPaymentProcessing(String messageText,
			final String phoneNo, final String accountNumber, final String amount,
			final String confirmationCode, final String payTo, final String datetime, 
			final OutgoingPayment.Status status) throws DuplicateKeyException {
		// then
		assertTrue(mpesaPaymentService instanceof EventObserver);
		
		// when
		mpesaPaymentService.notify(mockMessageNotification("MPESA", messageText));
		
		OutgoingPayment payment = new OutgoingPayment();
//		Set<Account> myAccounts = mockAccounts(accountNumber);
//		Client myClient = mockClient(1, phoneNo, myAccounts);
		payment.setClient(CLIENT_1);
		payment.setAmountPaid(new BigDecimal(amount));
		
		payment.setConfirmationCode(confirmationCode);
		payment.setTimeConfirmed(getTimestamp(datetime).getTime());
		payment.setStatus(status);
		payment.setPaymentServiceSettings(mpesaPaymentService.getSettings());
		
		// then
		WaitingJob.waitForEvent(1000);
		verify(outgoingPaymentDao).updateOutgoingPayment(payment);
	}
	
	protected void testBalanceProcessing(String messageText, String amount,
			String confimation_message, String date_time) {
		mpesaPaymentService.notify(mockMessageNotification("MPESA", messageText));
		
		WaitingJob.waitForEvent();
		//verify(mpesaPaymentService).setBalance(new BigDecimal(amount));
		assertEquals(mpesaPaymentService.getBalance().getBalanceAmount(), new BigDecimal(amount));
//		assertEquals(properties.getBalance(mpesaPaymentService).getBalanceAmount(), new BigDecimal(amount));
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
		mpesaPaymentService.notify(mockMessageNotification(fromNumber, messageText));
		
		// then
		WaitingJob.waitForEvent();
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
		// setup
		MpesaPaymentService s = this.mpesaPaymentService;
		
		// when
		s.notify(mockMessageNotification(from, messageText));
		
		// then
		WaitingJob.waitForEvent();
		verify(incomingPaymentDao, never()).saveIncomingPayment(any(IncomingPayment.class));
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
		when(ir.getPromptText()).thenReturn(title);
		
		StkRequest mockRequest = mock(StkRequest.class);
		when(ir.getRequest()).thenReturn(mockRequest);
		return ir;
	}
	
	private StkConfirmationPrompt mockConfirmation(String title) {
		StkConfirmationPrompt ir = mock(StkConfirmationPrompt.class);
		when(ir.getPromptText()).thenReturn(title);
		
		StkRequest mockRequest = mock(StkRequest.class);
		when(ir.getRequest()).thenReturn(mockRequest);
		return ir;
	}
	
	private EntitySavedNotification<FrontlineMessage> mockMessageNotification(String from, String text) {
		FrontlineMessage m = mock(FrontlineMessage.class);
		when(m.getSenderMsisdn()).thenReturn(from);
		when(m.getTextContent()).thenReturn(text);
		when(m.getEndpointId()).thenReturn("093SH5S655");
		return new EntitySavedNotification<FrontlineMessage>(m);
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
}

class WaitingJob extends FrontlineUiUpateJob {
	private boolean running;
	private void block() {
		running = true;
		execute();
		while(running) {
			try {
				Thread.sleep(200);
			} catch (InterruptedException e) {
				running = false;
			}
		}
	}
		
	private void block(int intstr) {
			running = true;
			execute();
			while(running) {
				try {
					Thread.sleep(intstr);
				} catch (InterruptedException e) {
					running = false;
				}
			}
	}
	
	public void run() {
		running = false;
	}
	
	/** Put a job on the UI event queue, and block until it has been run. */
	public static void waitForEvent() {
		new WaitingJob().block();
	}
	public static void waitForEvent(int intstr) {
		new WaitingJob().block(intstr);
	}
}