/*
 * (C) Copyright 2014 Kurento (http://kurento.org/)
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
package org.kurento.test.monitor;

import static org.kurento.commons.PropertiesManager.getProperty;
import static org.kurento.test.TestConfiguration.DEFAULT_MONITOR_RATE_DEFAULT;
import static org.kurento.test.TestConfiguration.DEFAULT_MONITOR_RATE_PROPERTY;
import static org.kurento.test.monitor.SystemMonitor.MONITOR_PORT_DEFAULT;
import static org.kurento.test.monitor.SystemMonitor.MONITOR_PORT_PROP;
import static org.kurento.test.monitor.SystemMonitor.OUTPUT_CSV;
import static org.kurento.test.TestConfiguration.KURENTO_KMS_LOGIN_PROP;
import static org.kurento.test.TestConfiguration.KURENTO_KMS_PASSWD_PROP;
import static org.kurento.test.TestConfiguration.KURENTO_KMS_PEM_PROP;
import static org.kurento.test.TestConfiguration.KMS_WS_URI_DEFAULT;
import static org.kurento.test.TestConfiguration.KMS_WS_URI_PROP;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Objects;

import org.kurento.test.services.SshConnection;
import org.openqa.selenium.JavascriptExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class to handle local or remote system monitor.
 *
 * @author Boni Garcia (bgarcia@gsyc.es)
 * @since 5.0.5
 */
public class SystemMonitorManager {

	public static Logger log = LoggerFactory
			.getLogger(SystemMonitorManager.class);

	private SystemMonitor monitor;
	private SshConnection remoteKms;
	private int monitorPort;

	public SystemMonitorManager() {
		try {
			String wsUri = getProperty(KMS_WS_URI_PROP, KMS_WS_URI_DEFAULT);
			String kmsLogin = getProperty(KURENTO_KMS_LOGIN_PROP);
			String kmsPasswd = getProperty(KURENTO_KMS_PASSWD_PROP);
			String kmsPem = getProperty(KURENTO_KMS_PEM_PROP);
			monitorPort = getProperty(MONITOR_PORT_PROP, MONITOR_PORT_DEFAULT);

			boolean isKmsRemote = !wsUri.contains("localhost")
					&& !wsUri.contains("127.0.0.1");

			if (isKmsRemote) {
				String remoteKmsStr = wsUri.substring(wsUri.indexOf("//") + 2,
						wsUri.lastIndexOf(":"));
				log.info("Using remote KMS at {}", remoteKmsStr);
				remoteKms = new SshConnection(remoteKmsStr, kmsLogin,
						kmsPasswd, kmsPem);
				remoteKms.start();
				remoteKms.createTmpFolder();
				copyMonitorToRemoteKms();
				startRemoteKms();
			}
			monitor = new SystemMonitor();

			int monitorRate = getProperty(DEFAULT_MONITOR_RATE_PROPERTY,
					DEFAULT_MONITOR_RATE_DEFAULT);
			monitor.setSamplingTime(monitorRate);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private void copyMonitorToRemoteKms() throws IOException,
			URISyntaxException {
		final String folder = "/org/kurento/test/monitor/";
		final String[] classesName = { "SystemMonitor.class",
				"SystemMonitor$1.class", "NetInfo.class",
				"NetInfo$NetInfoEntry.class", "SystemInfo.class" };

		Path tempDir = Files.createTempDirectory(null);
		File newDir = new File(tempDir + folder);
		newDir.mkdirs();
		String targetFolder = remoteKms.getTmpFolder() + folder;
		remoteKms.mkdirs(targetFolder);

		for (String className : classesName) {
			Path sourceClass = getPathInClasspath(folder + className);

			Path classFileInDisk = Files.createTempFile("", ".class");
			Files.copy(sourceClass, classFileInDisk,
					StandardCopyOption.REPLACE_EXISTING);
			remoteKms.scp(classFileInDisk.toString(), targetFolder + className);
			Files.delete(classFileInDisk);
		}
	}

	private void startRemoteKms() throws IOException {
		remoteKms.execCommand("sh", "-c",
				"java -cp " + remoteKms.getTmpFolder()
						+ " org.kurento.test.monitor.SystemMonitor "
						+ monitorPort + " > " + remoteKms.getTmpFolder()
						+ "/monitor.log 2>&1");

		// Wait for 600x100 ms = 60 seconds
		Socket client = null;
		int i = 0;
		final int max = 600;
		for (; i < max; i++) {
			try {
				client = new Socket(remoteKms.getHost(), monitorPort);
				break;
			} catch (ConnectException ce) {
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
				}
			}
		}
		if (client != null) {
			client.close();
		}
		if (i == max) {
			throw new RuntimeException("Socket in remote KMS not available");
		}
	}

	private Path getPathInClasspath(String resourceName) throws IOException,
			URISyntaxException {
		return getPathInClasspath(this.getClass().getResource(resourceName));
	}

	private Path getPathInClasspath(URL resource) throws IOException,
			URISyntaxException {
		Objects.requireNonNull(resource, "Resource URL cannot be null");
		URI uri = resource.toURI();

		String scheme = uri.getScheme();
		if (scheme.equals("file")) {
			return Paths.get(uri);
		}

		if (!scheme.equals("jar")) {
			throw new IllegalArgumentException("Cannot convert to Path: " + uri);
		}

		String s = uri.toString();
		int separator = s.indexOf("!/");
		String entryName = s.substring(separator + 2);
		URI fileURI = URI.create(s.substring(0, separator));

		FileSystem fs = null;

		try {
			fs = FileSystems.newFileSystem(fileURI,
					Collections.<String, Object> emptyMap());
		} catch (FileSystemAlreadyExistsException e) {
			fs = FileSystems.getFileSystem(fileURI);
		}

		return fs.getPath(entryName);
	}

	public void start() {
		if (remoteKms != null) {
			sendMessage("start");
		} else {
			monitor.start();
		}
	}

	public void writeResults(String csvFile) {
		if (remoteKms != null) {
			sendMessage("writeResults " + remoteKms.getTmpFolder());
			remoteKms.getFile(csvFile, remoteKms.getTmpFolder() + OUTPUT_CSV);
		} else {
			monitor.writeResults(csvFile);
		}
	}

	public void stop() {
		if (remoteKms != null) {
			sendMessage("stop");
		} else {
			monitor.stop();
		}
	}

	public void incrementNumClients() {
		if (remoteKms != null) {
			sendMessage("incrementNumClients");
		} else {
			monitor.incrementNumClients();
		}
	}

	public void decrementNumClients() {
		if (remoteKms != null) {
			sendMessage("decrementNumClients");
		} else {
			monitor.decrementNumClients();
		}
	}

	public void addCurrentLatency(long latency) throws IOException {
		if (remoteKms != null) {
			sendMessage("addCurrentLatency " + latency);
		} else {
			monitor.addCurrentLatency(latency);
		}
	}

	public void incrementLatencyErrors() throws IOException {
		if (remoteKms != null) {
			sendMessage("incrementLatencyErrors");
		} else {
			monitor.incrementLatencyErrors();
		}
	}

	public void setSamplingTime(long samplingTime) throws IOException {
		if (remoteKms != null) {
			sendMessage("setSamplingTime " + samplingTime);
		} else {
			monitor.setSamplingTime(samplingTime);
		}
	}

	private void sendMessage(String message) {
		try {
			// log.debug("Sending message {} to {}", message,
			// remoteKms.getHost());
			Socket client = new Socket(remoteKms.getHost(), monitorPort);
			PrintWriter output = new PrintWriter(client.getOutputStream(), true);
			BufferedReader input = new BufferedReader(new InputStreamReader(
					client.getInputStream()));
			// log.debug("Sending message to remote monitor: {}", message);
			output.println(message);

			String returnedMessage = input.readLine();

			if (returnedMessage != null) {
				// TODO handle errors
				// log.debug("Returned message by remote monitor: {}",
				// returnedMessage);
			}
			output.close();
			input.close();
			client.close();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public void destroy() {
		if (remoteKms != null) {
			sendMessage("destroy");
			remoteKms.stop();
		}
	}

	// TODO currently RTC stats are only supported in local monitor
	public void addJs(JavascriptExecutor js) {
		monitor.addJs(js);
	}

}
