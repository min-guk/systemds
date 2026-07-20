/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.runtime.controlprogram.federated;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.sysds.parser.DataExpression;
import org.apache.sysds.runtime.io.IOUtilFunctions;
import org.apache.sysds.runtime.meta.MetaDataAll;

/**
 * Resolves the raw privacy constraint for one federated worker while retaining
 * the evidence for any failed worker request.
 */
public final class FederatedPrivacyConstraintResolver {
	public enum Origin {
		LOCAL_PRE_REQUEST,
		WORKER_RESPONSE,
		LOCAL_POST_REQUEST,
		UNRESOLVED
	}

	public enum Failure {
		NONE,
		NULL_RESPONSE,
		NULL_PAYLOAD,
		ERROR_RESPONSE,
		REQUEST_EXCEPTION
	}

	/** Immutable outcome of resolving one worker's privacy constraint. */
	public record Resolution(String privacyConstraint, Origin origin, Failure failure,
		FederatedResponse response, Throwable cause) { }

	private FederatedPrivacyConstraintResolver() {
		// utility class
	}

	/**
	 * Resolve privacy locally when possible, otherwise issue exactly one worker
	 * request and retry the local metadata after any request failure.
	 *
	 * @param data federated worker metadata
	 * @return immutable resolution including original response or root cause
	 */
	public static Resolution resolve(FederatedData data) {
		String localPrivacy = readLocalPrivacy(data);
		if(localPrivacy != null)
			return new Resolution(localPrivacy, Origin.LOCAL_PRE_REQUEST, Failure.NONE, null, null);

		FederatedResponse response = null;
		Failure failure;
		Throwable cause = null;
		try {
			response = data.requestPrivacyConstraints().get();
			if(response == null)
				failure = Failure.NULL_RESPONSE;
			else if(!response.isSuccessful())
				failure = Failure.ERROR_RESPONSE;
			else {
				Object[] payload = response.getData();
				if(payload == null || payload.length == 0 || payload[0] == null)
					failure = Failure.NULL_PAYLOAD;
				else
					return new Resolution((String) payload[0], Origin.WORKER_RESPONSE,
						Failure.NONE, response, null);
			}
		}
		catch(Exception ex) {
			failure = Failure.REQUEST_EXCEPTION;
			cause = rootCause(ex);
			if(cause instanceof InterruptedException)
				Thread.currentThread().interrupt();
		}

		localPrivacy = readLocalPrivacy(data);
		Origin origin = localPrivacy != null ? Origin.LOCAL_POST_REQUEST : Origin.UNRESOLVED;
		return new Resolution(localPrivacy, origin, failure, response, cause);
	}

	private static Throwable rootCause(Throwable throwable) {
		Throwable cause = throwable;
		while((cause instanceof ExecutionException || cause instanceof CompletionException)
			&& cause.getCause() != null)
			cause = cause.getCause();
		return cause;
	}

	private static String readLocalPrivacy(FederatedData data) {
		if(data == null || data.getFilepath() == null)
			return null;
		String metadataFile = DataExpression.getMTDFileName(data.getFilepath());
		FileSystem fs = null;
		try {
			fs = IOUtilFunctions.getFileSystem(metadataFile);
			Path path = new Path(metadataFile);
			if(!fs.exists(path))
				return null;
			try(BufferedReader reader = new BufferedReader(new InputStreamReader(fs.open(path)))) {
				MetaDataAll metadata = new MetaDataAll(reader);
				return metadata.mtdExists() ? metadata.getPrivacyConstraints() : null;
			}
		}
		catch(Exception ex) {
			return null;
		}
		finally {
			IOUtilFunctions.closeSilently(fs);
		}
	}
}
