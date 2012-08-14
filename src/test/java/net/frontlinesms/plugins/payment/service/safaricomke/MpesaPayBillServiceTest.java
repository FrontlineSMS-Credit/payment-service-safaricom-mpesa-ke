package net.frontlinesms.plugins.payment.service.safaricomke;

import net.frontlinesms.plugins.payment.service.PaymentServiceException;
import net.frontlinesms.plugins.payment.service.safaricomke.MpesaPayBillService;

import org.creditsms.plugins.paymentview.data.domain.OutgoingPayment;

public class MpesaPayBillServiceTest extends
		MpesaPaymentServiceTest<MpesaPayBillService> {
	
	@Override
	protected MpesaPayBillService createNewTestClass() {
		return new MpesaPayBillService();
	}
	
	public void testMakePayment(){
		try{
			mpesaPaymentService.makePayment(new OutgoingPayment());
			fail("Should not be able to make payment with PayBill service");
		}catch (PaymentServiceException e) {
			// expected
		}
	}	
	
	public void testIncomingPayBillProcessing() throws Exception {
		testIncomingPaymentProcessing("BH45UU225 Confirmed.\n"
						+ "on 5/4/11 at 2:45 PM\n"
						+ "Ksh950.00 received from BORIS BECKER 254723908002.\n"
						+ "Account Number 0700000021\n"
						+ "New Utility balance is Ksh50,802.00",
				PHONENUMBER_2, ACCOUNTNUMBER_2_1, "0700000021",
				"950", "BH45UU225",
				"BORIS BECKER", "5/4/11 2:45 PM");
	
		//testing mpessa paybill message with paidby in lowercase  
		testIncomingPaymentProcessing("BH45UU225 Confirmed.\n"
						+ "on 5/4/11 at 2:45 PM\n"
						+ "Ksh950 received from ian mbogua kuburi 254723908002.\n"
						+ "Account Number 0700000021\n"
						+ "New Utility balance is Ksh50,802",
				PHONENUMBER_2, ACCOUNTNUMBER_2_1, "0700000021",
				"950", "BH45UU225",
				"ian mbogua kuburi", "5/4/11 2:45 PM");
		
		testIncomingPaymentProcessing("CJ87ZK061 Confirmed. \n" +
						"on 22/6/12 at 3:41 PM \n" +
						"Ksh50.00 received from KEN KITUYI 254700035870. \n" +
						"Account Number 88888 \n" +
						"New Utility balance is Ksh4,100.00",
						PHONENUMBER_3, null, "88888",
						"50", "CJ87ZK061", 
				"KEN KITUYI", "22/6/12 3:41 PM");
		
		// Check the payment time is processed rather than the balance time
		testIncomingPaymentProcessing("BHT57U225 Confirmed.\n"
						+ "on 10/4/11 at 1:45 PM\n"
						+ "Ksh123 received from ELLY ASAKHULU 254723908002.\n"
						+ "Account Number 0700000022\n"
						+ "New Utility balance is Ksh50,802",
				PHONENUMBER_2, ACCOUNTNUMBER_2_2, "0700000022",
				"123", "BHT57U225",
				"ELLY ASAKHULU", "10/4/11 1:45 PM");

		testIncomingPaymentProcessing("AB12AB123 Confirmed.\n" +
						"on 26/7/12 at 2:59 PM \n" +
						"Ksh20.00 received from BOB KIPLAGAT 254720123123.\n" +
						"Account Number 1235 \n" +
						"New Utility balance is Ksh7,240.00",
				"+254720123123", null/* no account created previously */, "1235",
				"20", "AB12AB123",
				"BOB KIPLAGAT", "26/7/12 2:59 PM");
			
		//testing mpesa paybill message with account number in upper case letters  
		testIncomingPaymentProcessing("FGRT8363D Confirmed.\n"
						+ "on 5/1/12 at 2:45 PM\n"
						+ "Ksh1350 received from LEO KIBWANA 254722035990.\n"
						+ "Account Number TEST\n"
						+ "New Utility balance is Ksh40,802",
				"+254722035990", ACCOUNTNUMBER_3_2, "TEST",
				"1350", "FGRT8363D",
				"LEO KIBWANA", "5/1/12 2:45 PM");
		
		//testing mpesa paybill message with account number in alphanumeric  
		testIncomingPaymentProcessing("BH85UU125 Confirmed.\n"
						+ "on 3/4/12 at 2:25 PM\n"
						+ "Ksh7950 received from LEO KIBWANA 254722035990.\n"
						+ "Account Number TEST76576\n"
						+ "New Utility balance is Ksh9,702",
				"+254722035990", ACCOUNTNUMBER_3_4, "TEST76576",
				"7950", "BH85UU125",
				"LEO KIBWANA", "3/4/12 2:25 PM");
		
		//testing mpesa paybill message with account number in lower case letters  
		testIncomingPaymentProcessing("XOY89U757 Confirmed.\n"
						+ "on 5/4/12 at 2:15 PM\n"
						+ "Ksh350 received from LEO KIBWANA 254722035990.\n"
						+ "Account Number testlower\n"
						+ "New Utility balance is Ksh10,802",
						"+254722035990", ACCOUNTNUMBER_3_1, "testlower",
						"350", "XOY89U757",
				"LEO KIBWANA", "5/4/12 2:15 PM");
		
		//testing mpesa paybill message with account number in upper and lower case letters  
		testIncomingPaymentProcessing("UII3267 Confirmed.\n"
						+ "on 3/1/12 at 2:45 PM\n"
						+ "Ksh3450 received from LEO KIBWANA 254722035990.\n"
						+ "Account Number TEstMixed\n"
						+ "New Utility balance is Ksh27,802",
				"+254722035990", ACCOUNTNUMBER_3_3, "TEstMixed",
				"3450", "UII3267",
				"LEO KIBWANA", "3/1/12 2:45 PM");
		
		//testing mpesa paybill message with account number in upper and lower case letters  
		testIncomingPaymentProcessing("UII3267 Confirmed.\n"
						+ "on 3/1/12 at 2:45 PM\n"
						+ "Ksh3450 received from MR IMPOSSIBLE 254999999999.\n"
						+ "Account Number with 2 spaces\n"
						+ "New Utility balance is Ksh27,802",
				"+254999999999", null, "with 2 spaces",
				"3450", "UII3267",
				"MR IMPOSSIBLE", "3/1/12 2:45 PM");
	}

	@Override
	String[] getValidMessagesText() {
		return new String[] {
				"Real text from a MPESA Paybill confirmation",
				"BH45UU225 Confirmed.\n"
						+ "on 5/4/11 at 2:45 PM\n"
						+ "Ksh950 received from BORIS BECKER 254723908002.\n"
						+ "Account Number 0700000021\n"
						+ "New Utility balance is Ksh50,802",
						
				"Test in case someone has only one name",
				"BHT57U225 Confirmed.\n"
						+ "on 5/4/11 at 1:45 PM\n"
						+ "Ksh123 received from ELLY 254723908002.\n"
						+ "Account Number 0700000022\n"
						+ "New Utility balance is Ksh50,802",
						
				"Test in case someone with three names",
				"BHT57U225 Confirmed.\n"
						+ "on 5/4/11 at 1:45 PM\n"
						+ "Ksh123 received from ELLY Y Tu 254723908002.\n"
						+ "Account Number 0700000022\n"
						+ "New Utility balance is Ksh50,802",
						
				"Test in case confirmation codes are made longer",
				"BHT57U225XXX Confirmed.\n"
						+ "on 5/4/11 at 1:45 PM\n"
						+ "Ksh123 received from ELLY 254723908002.\n"
						+ "Account Number 0700000022\n"
						+ "New Utility balance is Ksh50,802",

				"Test from Sharon's computer",
				"AB12AB123 Confirmed.\n" +
						"on 26/7/12 at 2:59 PM \n" +
						"Ksh20.00 received from BOB KIPLAGAT 254720123123.\n" +
						"Account Number 1235 \n" +
						"New Utility balance is Ksh7,240.00",
						
				"testing mpesa paybill message with account number in upper case letters",
				"FGRT8363D Confirmed.\n"
						+ "on 5/1/12 at 2:45 PM\n"
						+ "Ksh1350 received from LEO KIBWANA 254722035990.\n"
						+ "Account Number TEST\n"
						+ "New Utility balance is Ksh40,802",
				
				"testing mpesa paybill message with account number in alphanumeric",
				"BH85UU125 Confirmed.\n"
						+ "on 3/4/12 at 2:25 PM\n"
						+ "Ksh7950 received from LEO KIBWANA 254722035990.\n"
						+ "Account Number TEST76576\n"
						+ "New Utility balance is Ksh9,702",
				
				"testing mpesa paybill message with account number in upper and lower case letters",
				"UII3267 Confirmed.\n"
						+ "on 3/1/12 at 2:45 PM\n"
						+ "Ksh3450 received from LEO KIBWANA 254722035990.\n"
						+ "Account Number TEstMixed\n"
						+ "New Utility balance is Ksh27,802",
				
				"testing mpesa paybill message with account number in lower case letters", 
				"XOY89U757 Confirmed.\n"
						+ "on 5/4/12 at 2:15 PM\n"
						+ "Ksh350 received from LEO KIBWANA 254722035990.\n"
						+ "Account Number testlower\n"
						+ "New Utility balance is Ksh10,802",
						
				"account number containing spaces",
				"UII3267 Confirmed.\n"
						+ "on 3/1/12 at 2:45 PM\n"
						+ "Ksh3450 received from LEO KIBWANA 254722035990.\n"
						+ "Account Number with2spaces\n"
						+ "New Utility balance is Ksh27,802",
		};
	}
	
	@Override
	String[] getInvalidMessagesText() {
		return new String[] {
				"No newline after 'Confirmed.'",
				"BH45UU225 Confirmed."
						+ "on 5/4/11 at 2:45 PM\n"
						+ "Ksh950 received from BORIS BECKER 254723908002.\n"
						+ "Account Number 0700000021\n"
						+ "New Utility balance is Ksh50,802",
						
				"American Christmas - day and month exchanged in date format",
				"BHT57U225 Confirmed.\n"
						+ "on 12/25/11 at 1:45 PM\n"
						+ "Ksh123 received from ELLY ASAKHULU 254723908002.\n"
						+ "Account Number 0700000022\n"
						+ "New Utility balance is Ksh50,802",
						
				"29 undecimber",
				"BHT57U225XXX Confirmed.\n"
						+ "on 29/13/11 at 1:45 PM\n"
						+ "Ksh123 received from ELLY 254723908002.\n"
						+ "Account Number 0700000022\n"
						+ "New Utility balance is Ksh50,802",
		};
	}
}
