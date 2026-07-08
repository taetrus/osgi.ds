package com.kk.pde.ds.ecf.consumer;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kk.pde.ds.ecf.api.IRemoteGreet;

/**
 * Consumer-side component that injects the <em>remote</em> {@link IRemoteGreet}
 * service and invokes it as soon as it is bound.
 *
 * <p>
 * From DS's point of view this is an ordinary {@code @Reference} injection — the
 * exact same pattern the local {@code App} uses for {@code IGreet}. The magic is
 * that there is no local provider of {@code IRemoteGreet} in this process.
 * Instead, ECF's Remote Service Admin reads the EDEF endpoint description in
 * {@code OSGI-INF/remote-service/remote-greet-endpoint.xml} (referenced from the
 * {@code Remote-Service} manifest header), connects to the host's Generic-server
 * socket, and registers a <strong>proxy</strong> {@code IRemoteGreet} service.
 * The {@code @Reference} binds to that proxy, so {@link #bindGreet} runs a real
 * method call across the network to the host JVM.
 * </p>
 *
 * <p>
 * {@link ReferencePolicy#DYNAMIC} is used because the remote proxy appears
 * asynchronously (after the socket connects), and {@link ReferenceCardinality}
 * is mandatory so the component only activates once the remote service is
 * actually available.
 * </p>
 *
 * <p>
 * The bind method only stores the proxy — the actual {@code greet("ECF")} call is
 * a network round-trip and runs from {@code @Activate} on a dedicated thread, so a
 * slow or stalled host cannot block SCR's actor thread (which would delay activation
 * of every other component in the framework).
 * </p>
 */
@Component
public class RemoteGreetConsumer {

	private static final Logger log = LoggerFactory.getLogger(RemoteGreetConsumer.class);

	private volatile IRemoteGreet greet;

	@Reference(policy = ReferencePolicy.DYNAMIC, cardinality = ReferenceCardinality.MANDATORY)
	public void bindGreet(IRemoteGreet greet) {
		this.greet = greet;
		log.info("Remote IRemoteGreet bound (proxy class: {})", greet.getClass().getName());
	}

	public void unbindGreet(IRemoteGreet greet) {
		if (this.greet == greet) {
			this.greet = null;
		}
		log.info("Remote IRemoteGreet unbound");
	}

	@Activate
	public void activate() {
		final IRemoteGreet proxy = greet; // MANDATORY reference: bound before activate
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				if (proxy == null) {
					return;
				}
				log.info("Invoking remote greet(\"ECF\")...");
				try {
					String response = proxy.greet("ECF");
					log.info("Remote response: {}", response);
				} catch (Exception e) {
					log.error("Remote invocation failed", e);
				}
			}
		}, "ecf-consumer-greet");
		t.setDaemon(true);
		t.start();
	}
}
