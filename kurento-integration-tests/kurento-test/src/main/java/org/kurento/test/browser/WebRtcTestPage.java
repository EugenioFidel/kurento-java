/*
 * (C) Copyright 2015 Kurento (http://kurento.org/)
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 */
package org.kurento.test.browser;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.commons.codec.binary.Base64;
import org.junit.After;
import org.kurento.client.EventListener;
import org.kurento.client.OnIceCandidateEvent;
import org.kurento.client.WebRtcEndpoint;
import org.kurento.jsonrpc.JsonUtils;
import org.kurento.test.grid.GridHandler;
import org.kurento.test.latency.VideoTagType;
import org.kurento.test.services.KurentoServicesTestHelper;
import org.kurento.test.services.Recorder;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.support.ui.ExpectedCondition;
import org.openqa.selenium.support.ui.WebDriverWait;

/**
 * Specific client for tests within kurento-test project. This logic is linked
 * to client page logic (e.g. webrtc.html).
 *
 * @author Boni Garcia (bgarcia@gsyc.es)
 * @since 5.1.0
 */
public class WebRtcTestPage extends WebPage {

	protected static final String LOCAL_VIDEO = "local";
	protected static final String REMOTE_VIDEO = "video";

	private List<Thread> callbackThreads = new ArrayList<>();
	private Map<String, CountDownLatch> countDownLatchEvents = new HashMap<>();

	@After
	@SuppressWarnings("deprecation")
	public void teardownKurentoServices() throws Exception {
		for (Thread t : callbackThreads) {
			t.stop();
		}
	}

	public WebRtcTestPage() {
	}

	@Override
	public void setBrowser(Browser browserClient) {
		super.setBrowser(browserClient);

		// By default all tests are going to track color in both video tags
		checkColor(LOCAL_VIDEO, REMOTE_VIDEO);

		VideoTagType.setLocalId(LOCAL_VIDEO);
		VideoTagType.setRemoteId(REMOTE_VIDEO);
	}

	/*
	 * setColorCoordinates
	 */
	public void setColorCoordinates(int x, int y) {
		browser.getWebDriver().findElement(By.id("x")).clear();
		browser.getWebDriver().findElement(By.id("y")).clear();
		browser.getWebDriver().findElement(By.id("x")).sendKeys(String.valueOf(x));
		browser.getWebDriver().findElement(By.id("y")).sendKeys(String.valueOf(y));
		super.setColorCoordinates(x, y);
	}

	/*
	 * similarColor
	 */
	public boolean similarColor(Color expectedColor) {
		return similarColor(REMOTE_VIDEO, expectedColor);

	}

	/*
	 * similarColorAt
	 */
	public boolean similarColorAt(Color expectedColor, int x, int y) {
		return similarColorAt(REMOTE_VIDEO, expectedColor, x, y);
	}

	/*
	 * close
	 */
	public void close() {
		browser.close();
	}

	/*
	 * subscribeEvents
	 */
	public void subscribeEvents(String eventType) {
		subscribeEventsToVideoTag("video", eventType);
	}

	/*
	 * subscribeLocalEvents
	 */
	public void subscribeLocalEvents(String eventType) {
		subscribeEventsToVideoTag("local", eventType);
	}

	/*
	 * subscribeEventsToVideoTag
	 */
	public void subscribeEventsToVideoTag(final String videoTag, final String eventType) {
		CountDownLatch latch = new CountDownLatch(1);

		final String browserName = browser.getId();
		log.info("Subscribe event '{}' in video tag '{}' in browser '{}'", eventType, videoTag, browserName);

		countDownLatchEvents.put(browserName + eventType, latch);
		addEventListener(videoTag, eventType, new BrowserEventListener() {
			@Override
			public void onEvent(String event) {
				consoleLog(ConsoleLogLevel.INFO, "Event in " + videoTag + " tag: " + event);
				countDownLatchEvents.get(browserName + eventType).countDown();
			}
		});
	}

	/*
	 * waitForEvent
	 */
	public boolean waitForEvent(final String eventType) throws InterruptedException {

		String browserName = browser.getId();
		log.info("Waiting for event '{}' in browser '{}'", eventType, browserName);

		if (!countDownLatchEvents.containsKey(browserName + eventType)) {
			log.error("We cannot wait for an event without previous subscription");
			return false;
		}

		boolean result = countDownLatchEvents.get(browserName + eventType).await(browser.getTimeout(),
				TimeUnit.SECONDS);

		// Record local audio when playing event reaches the browser
		if (eventType.equalsIgnoreCase("playing") && browser.getRecordAudio() > 0) {
			if (browser.isRemote()) {
				Recorder.recordRemote(GridHandler.getInstance().getNode(browser.getId()),
						browser.getRecordAudio(), browser.getAudioSampleRate(),
						browser.getAudioChannel());
			} else {
				Recorder.record(browser.getRecordAudio(), browser.getAudioSampleRate(),
						browser.getAudioChannel());
			}
		}

		countDownLatchEvents.remove(browserName + eventType);
		return result;
	}

	/*
	 * addEventListener
	 */
	@SuppressWarnings("deprecation")
	public void addEventListener(final String videoTag, final String eventType,
			final BrowserEventListener eventListener) {
		Thread t = new Thread() {
			public void run() {
				browser.executeScript(videoTag + ".addEventListener('" + eventType + "', videoEvent, false);");
				try {
					(new WebDriverWait(browser.getWebDriver(), browser.getTimeout()))
							.until(new ExpectedCondition<Boolean>() {
						public Boolean apply(WebDriver d) {
							return d.findElement(By.id("status")).getAttribute("value").equalsIgnoreCase(eventType);
						}
					});
					eventListener.onEvent(eventType);
				} catch (Throwable t) {
					log.error("~~~ Exception in addEventListener {}", t.getMessage());
					t.printStackTrace();
					this.interrupt();
					this.stop();
				}
			}
		};
		callbackThreads.add(t);
		t.setDaemon(true);
		t.start();
	}

	/*
	 * start
	 */
	public void start(String videoUrl) {
		browser.executeScript("play('" + videoUrl + "', false);");
	}

	/*
	 * stop
	 */
	public void stopPlay() {
		browser.executeScript("terminate();");
	}

	/*
	 * consoleLog
	 */
	public void consoleLog(ConsoleLogLevel level, String message) {
		log.info(message);
		browser.executeScript("console." + level.toString() + "('" + message + "');");
	}

	/*
	 * getCurrentTime
	 */
	public double getCurrentTime() {
		log.debug("getCurrentTime() called");
		double currentTime = Double
				.parseDouble(browser.getWebDriver().findElement(By.id("currentTime")).getAttribute("value"));
		log.debug("getCurrentTime() result: {}", currentTime);
		return currentTime;
	}

	/*
	 * readConsole
	 */
	public String readConsole() {
		return browser.getWebDriver().findElement(By.id("console")).getText();
	}

	/*
	 * compare
	 */
	public boolean compare(double i, double j) {
		return Math.abs(j - i) <= browser.getThresholdTime();
	}

	/*
	 * initWebRtc
	 */
	@SuppressWarnings("deprecation")
	public void initWebRtc(final WebRtcEndpoint webRtcEndpoint, final WebRtcChannel channel, final WebRtcMode mode)
			throws InterruptedException {

		webRtcEndpoint.addOnIceCandidateListener(new EventListener<OnIceCandidateEvent>() {
			@Override
			public void onEvent(OnIceCandidateEvent event) {
				browser.executeScript("addIceCandidate('" + JsonUtils.toJsonObject(event.getCandidate()) + "');");
			}
		});

		final CountDownLatch latch = new CountDownLatch(1);
		Thread t = new Thread() {
			public void run() {
				initWebRtcSdpProcessor(new SdpOfferProcessor() {
					@Override
					public String processSdpOffer(String sdpOffer) {
						return webRtcEndpoint.processOffer(sdpOffer);
					}
				}, channel, mode);
				latch.countDown();
			}
		};
		t.start();
		if (!latch.await(browser.getTimeout(), TimeUnit.SECONDS)) {
			t.interrupt();
			t.stop();
		}
		webRtcEndpoint.gatherCandidates();
	}

	/*
	 * reload
	 */
	public void reload() {
		browser.reload();
		browser.injectKurentoTestJs();
		browser.executeScriptAndWaitOutput("return kurentoTest;");
		setBrowser(browser);
	}

	/*
	 * stopWebRtc
	 */
	public void stopWebRtc() {
		browser.executeScript("stop();");
		browser.executeScript("var kurentoTest = new KurentoTest();");
		countDownLatchEvents.clear();
	}

	/*
	 * initWebRtcSdpProcessor
	 */
	public void initWebRtcSdpProcessor(SdpOfferProcessor sdpOfferProcessor, WebRtcChannel channel, WebRtcMode mode) {

		// Append WebRTC mode (send/receive and audio/video) to identify test
		addTestName(KurentoServicesTestHelper.getTestCaseName() + "." + KurentoServicesTestHelper.getTestName());
		appendStringToTitle(mode.toString());
		appendStringToTitle(channel.toString());

		// Setting custom audio stream (if necessary)
		String audio = browser.getAudio();
		if (audio != null) {
			browser.executeScript("setCustomAudio('" + audio + "');");
		}

		// Setting MediaConstraints (if necessary)
		String channelJsFunction = channel.getJsFunction();
		if (channelJsFunction != null) {
			browser.executeScript(channelJsFunction);
		}

		// Execute JavaScript kurentoUtils.WebRtcPeer
		browser.executeScript(mode.getJsFunction());

		// Wait to valid sdpOffer
		String sdpOffer = (String) browser.executeScriptAndWaitOutput("return sdpOffer;");
		String sdpAnswer = sdpOfferProcessor.processSdpOffer(sdpOffer);

		log.trace("**** SDP OFFER: {}", sdpOffer);
		log.trace("**** SDP ANSWER: {}", sdpAnswer);

		// Encoding in Base64 to avoid parsing errors in JavaScript
		sdpAnswer = new String(Base64.encodeBase64(sdpAnswer.getBytes()));

		// Process sdpAnswer
		browser.executeScript("processSdpAnswer('" + sdpAnswer + "');");

	}

	/*
	 * addTestName
	 */
	public void addTestName(String testName) {
		try {
			browser.executeScript("addTestName('" + testName + "');");
		} catch (WebDriverException we) {
			log.warn(we.getMessage());
		}
	}

	/*
	 * appendStringToTitle
	 */
	public void appendStringToTitle(String webRtcMode) {
		try {
			browser.executeScript("appendStringToTitle('" + webRtcMode + "');");
		} catch (WebDriverException we) {
			log.warn(we.getMessage());
		}
	}

}