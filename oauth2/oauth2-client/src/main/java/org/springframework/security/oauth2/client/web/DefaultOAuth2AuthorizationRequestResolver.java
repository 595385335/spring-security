/*
 * Copyright 2002-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.security.oauth2.client.web;

import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.security.web.util.UrlUtils;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.util.Assert;
import org.springframework.web.util.UriComponentsBuilder;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * An implementation of an {@link OAuth2AuthorizationRequestResolver} that attempts to
 * resolve an {@link OAuth2AuthorizationRequest} from the provided {@code HttpServletRequest}
 * using the default request {@code URI} pattern {@code /oauth2/authorization/{registrationId}}.
 *
 * <p>
 * <b>NOTE:</b> The default base {@code URI} {@code /oauth2/authorization} may be overridden
 * via it's constructor {@link #DefaultOAuth2AuthorizationRequestResolver(ClientRegistrationRepository, String)}.
 *
 * @author Joe Grandja
 * @author Rob Winch
 * @since 5.1
 * @see OAuth2AuthorizationRequestResolver
 * @see OAuth2AuthorizationRequestRedirectFilter
 */
public final class DefaultOAuth2AuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {
	private static final String REGISTRATION_ID_URI_VARIABLE_NAME = "registrationId";
	private final ClientRegistrationRepository clientRegistrationRepository;
	private final AntPathRequestMatcher authorizationRequestMatcher;
	private final StringKeyGenerator stateGenerator = new Base64StringKeyGenerator(Base64.getUrlEncoder());
	private final StringKeyGenerator codeVerifierGenerator = new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 96);

	/**
	 * Constructs a {@code DefaultOAuth2AuthorizationRequestResolver} using the provided parameters.
	 *
	 * @param clientRegistrationRepository the repository of client registrations
	 * @param authorizationRequestBaseUri the base {@code URI} used for resolving authorization requests
	 */
	public DefaultOAuth2AuthorizationRequestResolver(ClientRegistrationRepository clientRegistrationRepository,
														String authorizationRequestBaseUri) {
		Assert.notNull(clientRegistrationRepository, "clientRegistrationRepository cannot be null");
		Assert.hasText(authorizationRequestBaseUri, "authorizationRequestBaseUri cannot be empty");
		this.clientRegistrationRepository = clientRegistrationRepository;
		this.authorizationRequestMatcher = new AntPathRequestMatcher(
				authorizationRequestBaseUri + "/{" + REGISTRATION_ID_URI_VARIABLE_NAME + "}");
	}

	@Override
	public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
		String registrationId = this.resolveRegistrationId(request);
		String redirectUriAction = getAction(request, "login");
		return resolve(request, registrationId, redirectUriAction);
	}

	@Override
	public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
		if (registrationId == null) {
			return null;
		}
		String redirectUriAction = getAction(request, "authorize");
		return resolve(request, registrationId, redirectUriAction);
	}

	private String getAction(HttpServletRequest request, String defaultAction) {
		String action = request.getParameter("action");
		if (action == null) {
			return defaultAction;
		}
		return action;
	}

	private OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId, String redirectUriAction) {
		if (registrationId == null) {
			return null;
		}

		ClientRegistration clientRegistration = this.clientRegistrationRepository.findByRegistrationId(registrationId);
		if (clientRegistration == null) {
			throw new IllegalArgumentException("Invalid Client Registration with Id: " + registrationId);
		}

		Map<String, Object> attributes = new HashMap<>();
		attributes.put(OAuth2ParameterNames.REGISTRATION_ID, clientRegistration.getRegistrationId());

		OAuth2AuthorizationRequest.Builder builder;
		if (AuthorizationGrantType.AUTHORIZATION_CODE.equals(clientRegistration.getAuthorizationGrantType())) {
			builder = OAuth2AuthorizationRequest.authorizationCode();
			if (ClientAuthenticationMethod.NONE.equals(clientRegistration.getClientAuthenticationMethod())) {
				Map<String, Object> additionalParameters = new HashMap<>();
				addPkceParameters(attributes, additionalParameters);
				builder.additionalParameters(additionalParameters);
			}
		} else if (AuthorizationGrantType.IMPLICIT.equals(clientRegistration.getAuthorizationGrantType())) {
			builder = OAuth2AuthorizationRequest.implicit();
		} else {
			throw new IllegalArgumentException("Invalid Authorization Grant Type ("  +
					clientRegistration.getAuthorizationGrantType().getValue() +
					") for Client Registration with Id: " + clientRegistration.getRegistrationId());
		}

		String redirectUriStr = this.expandRedirectUri(request, clientRegistration, redirectUriAction);

		OAuth2AuthorizationRequest authorizationRequest = builder
				.clientId(clientRegistration.getClientId())
				.authorizationUri(clientRegistration.getProviderDetails().getAuthorizationUri())
				.redirectUri(redirectUriStr)
				.scopes(clientRegistration.getScopes())
				.state(this.stateGenerator.generateKey())
				.attributes(attributes)
				.build();

		return authorizationRequest;
	}

	private String resolveRegistrationId(HttpServletRequest request) {
		if (this.authorizationRequestMatcher.matches(request)) {
			return this.authorizationRequestMatcher
					.extractUriTemplateVariables(request).get(REGISTRATION_ID_URI_VARIABLE_NAME);
		}
		return null;
	}

	private String expandRedirectUri(HttpServletRequest request, ClientRegistration clientRegistration, String action) {
		// Supported URI variables -> baseUrl, action, registrationId
		// Used in -> CommonOAuth2Provider.DEFAULT_REDIRECT_URL = "{baseUrl}/{action}/oauth2/code/{registrationId}"
		Map<String, String> uriVariables = new HashMap<>();
		uriVariables.put("registrationId", clientRegistration.getRegistrationId());
		String baseUrl = UriComponentsBuilder.fromHttpUrl(UrlUtils.buildFullRequestUrl(request))
				.replaceQuery(null)
				.replacePath(request.getContextPath())
				.build()
				.toUriString();
		uriVariables.put("baseUrl", baseUrl);
		if (action != null) {
			uriVariables.put("action", action);
		}
		return UriComponentsBuilder.fromUriString(clientRegistration.getRedirectUriTemplate())
				.buildAndExpand(uriVariables)
				.toUriString();
	}

	/**
	 * Creates and adds additional PKCE parameters for use in the OAuth 2.0 Authorization and Access Token Requests
	 *
	 * @param attributes where {@link PkceParameterNames#CODE_VERIFIER} is stored for the token request
	 * @param additionalParameters where {@link PkceParameterNames#CODE_CHALLENGE} and, usually,
	 * {@link PkceParameterNames#CODE_CHALLENGE_METHOD} are added to be used in the authorization request.
	 *
	 * @since 5.2
	 * @see <a target="_blank" href="https://tools.ietf.org/html/rfc7636#section-1.1">1.1.  Protocol Flow</a>
	 * @see <a target="_blank" href="https://tools.ietf.org/html/rfc7636#section-4.1">4.1.  Client Creates a Code Verifier</a>
	 * @see <a target="_blank" href="https://tools.ietf.org/html/rfc7636#section-4.2">4.2.  Client Creates the Code Challenge</a>
	 */
	private void addPkceParameters(Map<String, Object> attributes, Map<String, Object> additionalParameters) {
		String codeVerifier = this.codeVerifierGenerator.generateKey();
		attributes.put(PkceParameterNames.CODE_VERIFIER, codeVerifier);
		try {
			String codeChallenge = createCodeChallenge(codeVerifier);
			additionalParameters.put(PkceParameterNames.CODE_CHALLENGE, codeChallenge);
			additionalParameters.put(PkceParameterNames.CODE_CHALLENGE_METHOD, "S256");
		} catch (NoSuchAlgorithmException e) {
			additionalParameters.put(PkceParameterNames.CODE_CHALLENGE, codeVerifier);
		}
	}

	private String createCodeChallenge(String codeVerifier) throws NoSuchAlgorithmException {
		MessageDigest md = MessageDigest.getInstance("SHA-256");
		byte[] digest = md.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
		return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
	}
}