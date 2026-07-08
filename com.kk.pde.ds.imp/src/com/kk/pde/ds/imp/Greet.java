package com.kk.pde.ds.imp;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kk.pde.ds.api.IGreet;

@Component
public class Greet implements IGreet {

	private static final Logger log = LoggerFactory.getLogger(Greet.class);

	@Activate
	public void start() {
		log.info("Greet.start()");
	}

	@Override
	public void greet() {
		log.info(greeting());
	}

	/**
	 * The greeting text. Extracted as a testable seam so tests can assert on the
	 * message without capturing log output.
	 */
	public String greeting() {
		return "Hello world!";
	}

}
