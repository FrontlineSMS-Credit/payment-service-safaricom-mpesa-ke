/**
 * 
 */
package net.frontlinesms.payment.safaricom;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import net.frontlinesms.data.DuplicateKeyException;

import org.creditsms.plugins.paymentview.data.domain.OutgoingPayment;

import static org.mockito.Mockito.*;

public class MpesaPersonalServiceTest extends MpesaPaymentServiceTest<MpesaPersonalService> {
	private ArrayList<OutgoingPayment> OUTGOING_LIST_0;
	private ArrayList<OutgoingPayment> OUTGOING_LIST_1;

	@Override
	protected MpesaPersonalService createNewTestClass() {
		return new MpesaPersonalService();
	}
	
	protected void init() {
		OutgoingPayment outgoingPayment = new OutgoingPayment();
		outgoingPayment.setClient(CLIENT_1);
		outgoingPayment.setAmountPaid(new BigDecimal("1235"));
		outgoingPayment.setConfirmationCode("BC77RI604");
		outgoingPayment.setStatus(OutgoingPayment.Status.UNCONFIRMED);
		outgoingPayment.setPaymentServiceSettings(mpesaPaymentService.getSettings());
		
		
		OUTGOING_LIST_0 = new ArrayList<OutgoingPayment>();
		OUTGOING_LIST_1 = new ArrayList<OutgoingPayment>();
		OUTGOING_LIST_1.add(outgoingPayment);
		
		mockOutgoingPaymentsDao(PHONENUMBER_0, new BigDecimal("1235"), OUTGOING_LIST_0);
		mockOutgoingPaymentsDao(PHONENUMBER_1, new BigDecimal("1235"), OUTGOING_LIST_1);
		mockOutgoingPaymentsDao(PHONENUMBER_2, new BigDecimal("1235"), new ArrayList<OutgoingPayment>());
	}
	
	private void mockOutgoingPaymentsDao(String phoneNumber, BigDecimal amountPaid, List<OutgoingPayment> Return_List) {
		when(outgoingPaymentDao.getByPhoneNumberAndAmountPaid
				(phoneNumber, amountPaid, OutgoingPayment.Status.UNCONFIRMED)
		).thenReturn(Return_List);
	}
	
	public void testValidBalanceFraudCheck() throws DuplicateKeyException {
		balance.reset();
		// 30 kes transaction fees
		balance.setBalanceAmount("2265");
		balance.updateBalance();
		//Test When Payment is successful OutgoingPayment
		testOutgoingPaymentProcessing("BC77RI604 Confirmed. " +
				"Ksh1,235 sent to DACON OMONDI +254723908001 on 22/5/11 at 10:35 PM " +
				"New M-PESA balance is Ksh1,000",
				PHONENUMBER_1, ACCOUNTNUMBER_1_1, "1235", "BC77RI604",
				"DACON OMONDI", "22/5/11 10:35 PM", OutgoingPayment.Status.CONFIRMED);
		
		verify(logger).info("No Fraud occured!");
		
		balance.reset();
		balance.setBalanceAmount("1000");
		balance.updateBalance();
		
		//Test When Payment is successful IncomingPayment
		testIncomingPaymentProcessing("BI94HR849 Confirmed.\n" +
				"You have received Ksh236 from\nJOHN KIU 254723908001\non 30/5/11 at 10:35 PM\n" +
				"New M-PESA balance is Ksh1,236",
				PHONENUMBER_1, ACCOUNTNUMBER_1_1, "236", "BI94HR849",
				"JOHN KIU", "30/5/11 10:35 PM");
		
		verify(logger, times(2)).info("No Fraud occured!");
	}
	
	public void testInvalidBalanceFraudCheck() throws DuplicateKeyException {
		balance.reset();
		balance.setBalanceAmount("4265");
		balance.updateBalance();
		//Test When Payment is successful OutgoingPayment //Ksh100 lost
		testOutgoingPaymentProcessing("BC77RI604 Confirmed. " +
				"Ksh1,235 sent to DACON OMONDI +254723908001 on 22/5/11 at 10:35 PM " +
				"New M-PESA balance is Ksh5,500",
				PHONENUMBER_1, ACCOUNTNUMBER_1_1, "1235", "BC77RI604",
				"DACON OMONDI", "22/5/11 10:35 PM", OutgoingPayment.Status.CONFIRMED);
		
		verify(logger, never()).info("No Fraud occured!");
	}
	
	public void testOutgoingPaymentProcessing() throws DuplicateKeyException {
		balance.reset();
		balance.setBalanceAmount("2265");
		balance.updateBalance();
		
		testOutgoingPaymentProcessing("BC77RI604 Confirmed. " +
				"Ksh1,235 sent to DACON OMONDI +254723908001 on 22/5/11 at 10:35 PM " +
				"New M-PESA balance is Ksh1,000",
				PHONENUMBER_1, ACCOUNTNUMBER_1_1, "1235", "BC77RI604",
				"DACON OMONDI", "22/5/11 10:35 PM", OutgoingPayment.Status.CONFIRMED);
	}
	
	public void testIncomingPaymentProcessingWithNoAccount() {
		testIncomingPaymentProcessorIgnoresMessage("+254721656788", "BI94HR849 Confirmed.\n" +
				"You have received Ksh2,000 from\nJOHN KIU 254723908000\non 3/5/11 at 10:35 PM\n" +
				"New M-PESA balance is Ksh5,000");
				
		testIncomingPaymentProcessorIgnoresMessage("+254701103438", "BL99BD339 Confirmed.\nYou have received Ksh50.00 from\n"+
				"ROY ONYANGO 254701103438\non 29/6/11 at 1:19 PM\nNew M-PESA balance is Ksh67,236");
	}
	
	public void testIncomingPaymentProcessing() {
		balance.reset();
		balance.setBalanceAmount("1");
		balance.updateBalance();
		
		testIncomingPaymentProcessing("BI94HR849 Confirmed.\n" +
				"You have received Ksh1,235 from\nJOHN KIU 254723908001\non 30/5/11 at 10:35 PM\n" +
				"New M-PESA balance is Ksh1,236",
				PHONENUMBER_1, ACCOUNTNUMBER_1_1, "1235", "BI94HR849",
				"JOHN KIU", "30/5/11 10:35 PM");
		
		balance.reset();
		balance.setBalanceAmount("1");
		balance.updateBalance();
		
		testIncomingPaymentProcessing("BI94HR849 Confirmed.\n" +
				"You have received Ksh1,235 from\nyohan mwenyewe alibamba 254723908001\non 3/5/11 at 8:35 PM\n" +
				"New M-PESA balance is Ksh1,236",
				PHONENUMBER_1, ACCOUNTNUMBER_1_1, "1235", "BI94HR849",
				"yohan mwenyewe alibamba", "3/5/11 8:35 PM");
	}
	
	public void testBalanceProcessing(){
		balance.reset();
		balance.setBalanceAmount("1235");
		balance.updateBalance();
		
		testBalanceProcessing("NB56GF6JK Confirmed.\n" +
			"Your M-PESA balance was Ksh1,235\n" +
			"on 12/2/11 at 12:23 AM",
		"1235", "NB56GF6JK", "12/2/11 12:23 AM");
	}

	@Override
	String[] getValidMessagesText() {
		return new String[] {
				"Real text from a MPESA Standard confirmation",
				"BI94HR849 Confirmed.\n" +
						"You have received Ksh1,235 from\nJOHN KIU 254723908001\non 3/5/11 at 10:35 PM\n" +
						"New M-PESA balance is Ksh1,236",
						
				"Real text from a MPESA Standard confirmation; Testing 20/5/11",
				"BI94HR849 Confirmed.\n" +
						"You have received Ksh1,235 from\nJOHN KIU 254723908001\non 20/5/11 at 10:35 PM\n" +
						"New M-PESA balance is Ksh1,236",
						
				"Test in case someone has only one name",
				"BI94HR849 Confirmed.\n" +
						"You have received Ksh1,235 from\nKIU 254723908001\non 10/5/11 at 10:35 PM\n" +
						"New M-PESA balance is Ksh1,236",
						
				"Test in case confirmation codes are made longer",
				"BI94HR849XXX Confirmed.\n" +
						"You have received Ksh1,235 from\nJOHN KIU 254723908001\non 30/5/11 at 10:35 PM\n" +
						"New M-PESA balance is Ksh1,236",
		};
	}
	
	@Override
	String[] getInvalidMessagesText() {
		return new String[] {
				"No newline after 'Confirmed.'",
				"BI94HR84 9 Confirmed." +
				"You have " +
				"received Ksh1,235 from\nJOHN KIU 254723908001\non 3/5/11 at 10:35 PM\n" +
				"New M-PESA balance is Ksh1,236",
						
				"American Christmas",
				"BI94HR849 Confirmed.\n" +
				"You have received Ksh1,235 from\nJOHN KIU 254723908001\non 12/25/11 at 10:35 PM\n" +
				"New M-PESA balance is Ksh1,236",
		};
	}
}
