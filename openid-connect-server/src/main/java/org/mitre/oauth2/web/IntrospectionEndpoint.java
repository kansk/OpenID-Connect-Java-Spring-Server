/*******************************************************************************
 * Copyright 2015 The MITRE Corporation
 *   and the MIT Kerberos and Internet Trust Consortium
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package org.mitre.oauth2.web;

import java.util.Map;
import java.util.Set;

import org.mitre.oauth2.model.ClientDetailsEntity;
import org.mitre.oauth2.model.OAuth2AccessTokenEntity;
import org.mitre.oauth2.model.OAuth2RefreshTokenEntity;
import org.mitre.oauth2.service.ClientDetailsEntityService;
import org.mitre.oauth2.service.IntrospectionAuthorizer;
import org.mitre.oauth2.service.IntrospectionResultAssembler;
import org.mitre.oauth2.service.OAuth2TokenEntityService;
import org.mitre.oauth2.service.SystemScopeService;
import org.mitre.openid.connect.model.UserInfo;
import org.mitre.openid.connect.service.UserInfoService;
import org.mitre.openid.connect.view.HttpCodeView;
import org.mitre.openid.connect.view.JsonEntityView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.common.exceptions.InvalidTokenException;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;

import static org.mitre.oauth2.web.AuthenticationUtilities.ensureOAuthScope;

@Controller
public class IntrospectionEndpoint {

	@Autowired
	private OAuth2TokenEntityService tokenServices;

	@Autowired
	private ClientDetailsEntityService clientService;

	@Autowired
	private IntrospectionAuthorizer introspectionAuthorizer;

	@Autowired
	private IntrospectionResultAssembler introspectionResultAssembler;

	@Autowired
	private UserInfoService userInfoService;

	private static Logger logger = LoggerFactory.getLogger(IntrospectionEndpoint.class);

	public IntrospectionEndpoint() {

	}

	public IntrospectionEndpoint(OAuth2TokenEntityService tokenServices) {
		this.tokenServices = tokenServices;
	}

	@RequestMapping("/introspect")
	public String verify(@RequestParam("token") String tokenValue,
			@RequestParam(value = "token_type_hint", required = false) String tokenType,
			Authentication auth, Model model) {

		if (Strings.isNullOrEmpty(tokenValue)) {
			logger.error("Verify failed; token value is null");
			Map<String,Boolean> entity = ImmutableMap.of("active", Boolean.FALSE);
			model.addAttribute("entity", entity);
			return JsonEntityView.VIEWNAME;
		}

		ClientDetailsEntity authClient = null;
		
		if (auth instanceof OAuth2Authentication) {
			// the client authenticated with OAuth, do our UMA checks
			ensureOAuthScope(auth, SystemScopeService.UMA_PROTECTION_SCOPE);
			
			// get out the client that was issued the access token (not the token being introspected)
			OAuth2Authentication o2a = (OAuth2Authentication) auth;
			
			String authClientId = o2a.getOAuth2Request().getClientId();
			authClient = clientService.loadClientByClientId(authClientId);
			
		} else {
			// the client authenticated directly, make sure it's got the right access
			
			String authClientId = auth.getName(); // direct authentication puts the client_id into the authentication's name field
			authClient = clientService.loadClientByClientId(authClientId);

			if (!AuthenticationUtilities.hasRole(auth, "ROLE_CLIENT")
					|| !authClient.isAllowIntrospection()) {
				
				// this client isn't allowed to do direct introspection
				
				logger.error("Client " + authClient.getClientId() + " is not allowed to call introspection endpoint");
				model.addAttribute("code", HttpStatus.FORBIDDEN);
				return HttpCodeView.VIEWNAME;

			}
			
		}
		
		if (authClient == null) {
			// shouldn't ever get here, if the client's been authenticated by now it should exist
			logger.error("Introspection client wasn't found");
			model.addAttribute("code", HttpStatus.FORBIDDEN);
			return HttpCodeView.VIEWNAME;
		}

		// now we need to look up the token in our token stores
		
		OAuth2AccessTokenEntity accessToken = null;
		OAuth2RefreshTokenEntity refreshToken = null;
		ClientDetailsEntity tokenClient;
		Set<String> scopes;
		UserInfo user;

		try {

			// check access tokens first (includes ID tokens)
			accessToken = tokenServices.readAccessToken(tokenValue);

			tokenClient = accessToken.getClient();
			scopes = accessToken.getScope();

			// get the user information of the user that authorized this token in the first place
			String userName = accessToken.getAuthenticationHolder().getAuthentication().getName();
			user = userInfoService.getByUsernameAndClientId(userName, tokenClient.getClientId());

		} catch (InvalidTokenException e) {
			logger.info("Invalid access token. Checking refresh token.", e);
			try {

				// check refresh tokens next
				refreshToken = tokenServices.getRefreshToken(tokenValue);

				tokenClient = refreshToken.getClient();
				scopes = refreshToken.getAuthenticationHolder().getAuthentication().getOAuth2Request().getScope();

				// get the user information of the user that authorized this token in the first place
				String userName = refreshToken.getAuthenticationHolder().getAuthentication().getName();
				user = userInfoService.getByUsernameAndClientId(userName, tokenClient.getClientId());

			} catch (InvalidTokenException e2) {
				logger.error("Invalid refresh token", e2);
				Map<String,Boolean> entity = ImmutableMap.of("active", Boolean.FALSE);
				model.addAttribute("entity", entity);
				return JsonEntityView.VIEWNAME;
			}
		}

		if (introspectionAuthorizer.isIntrospectionPermitted(authClient, tokenClient, scopes)) {
			// if it's a valid token, we'll print out information on it
			
			if (accessToken != null) {
				Map<String, Object> entity = introspectionResultAssembler.assembleFrom(accessToken, user);
				model.addAttribute("entity", entity);
			} else if (refreshToken != null) {
				Map<String, Object> entity = introspectionResultAssembler.assembleFrom(refreshToken, user);
				model.addAttribute("entity", entity);
			} else {
				// no tokens were found (we shouldn't get here)
				logger.error("Verify failed; Invalid access/refresh token");
				Map<String,Boolean> entity = ImmutableMap.of("active", Boolean.FALSE);
				model.addAttribute("entity", entity);
				return JsonEntityView.VIEWNAME;
			}
			
			return JsonEntityView.VIEWNAME;
			
		} else {
			logger.error("Verify failed; client configuration or scope don't permit token introspection");
			model.addAttribute("code", HttpStatus.FORBIDDEN);
			return HttpCodeView.VIEWNAME;
		}
	}

}
