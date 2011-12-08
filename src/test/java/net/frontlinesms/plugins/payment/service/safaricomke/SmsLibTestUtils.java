package net.frontlinesms.plugins.payment.service.safaricomke;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.smslib.CService;
import org.smslib.SMSLibDeviceException;
import org.smslib.handler.ATHandler.SynchronizedWorkflow;

/** Clone of file from SMSLib, included here to avoid dependency complications.
 * TODO this should be included properly from SMSLib. */
public class SmsLibTestUtils {
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static CService mockCService() throws SMSLibDeviceException, IOException {
		CService s = mock(CService.class);
		// Make sure that synchronized jobs run on the CService actually get executed - 
		// otherwise the mock will just return null!
		when(s.doSynchronized(any(SynchronizedWorkflow.class))).thenAnswer(new Answer() {
			public Object answer(InvocationOnMock invocation) throws Throwable {
				return ((SynchronizedWorkflow<?>) invocation.getArguments()[0]).run();
			}
		});
		return s;
	}
}
