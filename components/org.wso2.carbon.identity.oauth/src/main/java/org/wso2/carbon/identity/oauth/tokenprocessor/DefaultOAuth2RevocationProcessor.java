/*
 * Copyright (c) 2023, WSO2 LLC. (https://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.oauth.tokenprocessor;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.identity.application.authentication.framework.model.AuthenticatedUser;
import org.wso2.carbon.identity.application.common.IdentityApplicationManagementException;
import org.wso2.carbon.identity.application.common.model.InboundAuthenticationRequestConfig;
import org.wso2.carbon.identity.application.common.model.ServiceProvider;
import org.wso2.carbon.identity.application.mgt.ApplicationManagementService;
import org.wso2.carbon.identity.oauth.OAuthUtil;
import org.wso2.carbon.identity.oauth.internal.OAuthComponentServiceHolder;
import org.wso2.carbon.identity.oauth2.IdentityOAuth2Exception;
import org.wso2.carbon.identity.oauth2.dao.OAuthTokenPersistenceFactory;
import org.wso2.carbon.identity.oauth2.dto.OAuthRevocationRequestDTO;
import org.wso2.carbon.identity.oauth2.model.AccessTokenDO;
import org.wso2.carbon.identity.oauth2.model.RefreshTokenValidationDataDO;
import org.wso2.carbon.identity.oauth2.util.OAuth2Util;
import org.wso2.carbon.user.core.UserStoreException;
import org.wso2.carbon.user.core.UserStoreManager;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.wso2.carbon.identity.oauth.common.OAuthConstants.Scope.OAUTH2;

/**
 * DefaultOAuth2RevocationProcessor is responsible for handling OAuth2 token revocation
 * when a persistence layer is in use. It provides methods to revoke access tokens and
 * refresh tokens, as well as a mechanism to revoke tokens associated with a specific user.
 */
public class DefaultOAuth2RevocationProcessor implements OAuth2RevocationProcessor {

    public static final Log LOG = LogFactory.getLog(DefaultOAuth2RevocationProcessor.class);

    @Override
    public void revokeAccessToken(OAuthRevocationRequestDTO revokeRequestDTO, AccessTokenDO accessTokenDO)
            throws IdentityOAuth2Exception {

        OAuthTokenPersistenceFactory.getInstance().getAccessTokenDAO()
                .revokeAccessTokens(new String[]{accessTokenDO.getAccessToken()});
    }

    @Override
    public void revokeRefreshToken(OAuthRevocationRequestDTO revokeRequestDTO,
                                   RefreshTokenValidationDataDO refreshTokenDO) throws IdentityOAuth2Exception {

        OAuthTokenPersistenceFactory.getInstance().getAccessTokenDAO()
                .revokeAccessTokens(new String[]{refreshTokenDO.getAccessToken()});
    }

    @Override
    public boolean revokeTokens(String username, UserStoreManager userStoreManager)
            throws UserStoreException {

        return OAuthUtil.revokeTokens(username, userStoreManager);
    }

    @Override
    public boolean revokeTokens(String username, UserStoreManager userStoreManager, String roleId)
            throws UserStoreException {

        return OAuthUtil.revokeTokens(username, userStoreManager, roleId);
    }

    /**
     * Revoke tokens associated with the specified application ID, API ID, removed scopes, and tenant domain.
     *
     * @param appId           The ID of the application.
     * @param apiId           The ID of the API.
     * @param removedScopes   The list of removed scopes.
     * @param tenantDomain    The tenant domain.
     * @throws IdentityOAuth2Exception If an error occurs while revoking tokens.
     */
    @Override
    public void revokeTokens(String appId, String apiId, List<String> removedScopes,
                             String tenantDomain) throws IdentityOAuth2Exception {

        // Calling ApplicationManagementService to get client Id for the app Id.
        ApplicationManagementService applicationManagementService =
                OAuthComponentServiceHolder.getInstance().getApplicationManagementService();
        String clientId = null;
        try {
            // Retrieving application by resource ID.
            ServiceProvider application = applicationManagementService.getApplicationByResourceId(appId, tenantDomain);
            if (application == null ||
                    application.getInboundAuthenticationConfig() == null ||
                    application.getInboundAuthenticationConfig().getInboundAuthenticationRequestConfigs() == null) {
                // If application or authentication configurations are null, do nothing.
                return;
            }

            // Retrieving client ID from inbound authentication configurations.
            for (InboundAuthenticationRequestConfig oauth2config :
                    application.getInboundAuthenticationConfig().getInboundAuthenticationRequestConfigs()) {
                if (StringUtils.equals(OAUTH2, oauth2config.getInboundAuthType())) {
                    clientId = oauth2config.getInboundAuthKey();
                    break;
                }
            }
            if (clientId == null) {
                // If client ID is not found, log error and throw exception.
                LOG.error(String.format("Invalid client of application : %s , ", application.getApplicationName()));
                throw new IdentityOAuth2Exception(String.format("Invalid client of application : %s , "
                        , application.getApplicationName()));
            }

            // Retrieve active access tokens for the given client ID and removed scopes.
            Set<AccessTokenDO> accessTokenDOSet = OAuthTokenPersistenceFactory.getInstance()
                    .getAccessTokenDAO().getActiveTokenSetWithTokenIdByConsumerKeyAndScope(clientId, removedScopes);

            // Iterate through the retrieved access tokens and revoke them.
            for (AccessTokenDO accessTokenDO: accessTokenDOSet) {
                revokeTokens(clientId, accessTokenDO.getAuthzUser(), accessTokenDO,
                        accessTokenDO.getTokenBinding().getBindingReference());
            }
        } catch (IdentityApplicationManagementException e) {
            LOG.error("Error occurred while retrieving app by app ID : " + appId, e);
            throw new IdentityOAuth2Exception("Error occurred while retrieving app by app ID : " + appId, e);
        }
    }

    /**
     * Revokes access tokens associated with the specified consumer key, user, access token data object,
     * and token binding reference.
     *
     * @param consumerKey             The consumer key of the application.
     * @param user                    The authenticated user.
     * @param accessTokenDO           The access token data object.
     * @param tokenBindingReference   The token binding reference.
     * @throws IdentityOAuth2Exception If an error occurs while revoking access tokens.
     */
    private void revokeTokens(String consumerKey, AuthenticatedUser user, AccessTokenDO accessTokenDO,
                                       String tokenBindingReference) throws IdentityOAuth2Exception {

        if (LOG.isDebugEnabled()) {
            LOG.debug("Revoking tokens for the application with consumerKey:" + consumerKey + " for the user: "
                    + user.getLoggableUserId());
        }
        OAuthUtil.clearOAuthCache(consumerKey, user, OAuth2Util.buildScopeString
                (accessTokenDO.getScope()), tokenBindingReference);
        OAuthUtil.clearOAuthCache(consumerKey, user, OAuth2Util.buildScopeString
                (accessTokenDO.getScope()));
        OAuthUtil.clearOAuthCache(consumerKey, user);
        OAuthUtil.clearOAuthCache(accessTokenDO);
        OAuthUtil.invokePreRevocationBySystemListeners(accessTokenDO, Collections.emptyMap());
        OAuthTokenPersistenceFactory.getInstance().getAccessTokenDAO()
                .revokeAccessTokens(new String[]{accessTokenDO.getAccessToken()}, OAuth2Util.isHashEnabled());
        OAuthUtil.invokePostRevocationBySystemListeners(accessTokenDO, Collections.emptyMap());
    }
}
