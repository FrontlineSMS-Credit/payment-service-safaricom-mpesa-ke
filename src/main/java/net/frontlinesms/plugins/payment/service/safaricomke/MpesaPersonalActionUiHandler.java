package net.frontlinesms.plugins.payment.service.safaricomke;

import net.frontlinesms.plugins.payment.service.ui.PaymentServiceUiActionHandler;
import net.frontlinesms.ui.UiGeneratorController;

public class MpesaPersonalActionUiHandler implements PaymentServiceUiActionHandler {
	private final MpesaPersonalService service;
	private final UiGeneratorController ui;
	
	public MpesaPersonalActionUiHandler(MpesaPersonalService service, UiGeneratorController ui) {
		this.service = service;
		this.ui = ui;
	}

	public boolean hasMenuItems() {
		return true;
	}

	public Object[] getMenuItems() {
		Object payBillMenuItem = ui.createMenuitem("", "make PayBill payment"); // TODO add icon FIXME i18n
		ui.setAction(payBillMenuItem, "launchPaybillWizard", payBillMenuItem, this);
		return new Object[] {
				payBillMenuItem 
		};
	}
	
	public void launchPaybillWizard() {
		ui.alert("Launch paybill wizard!");
	}
}
