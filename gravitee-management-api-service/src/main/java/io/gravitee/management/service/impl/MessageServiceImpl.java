/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.management.service.impl;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import io.gravitee.common.http.HttpMethod;
import io.gravitee.management.model.*;
import io.gravitee.management.service.*;
import io.gravitee.management.service.builder.EmailNotificationBuilder;
import io.gravitee.management.service.exceptions.ApiNotFoundException;
import io.gravitee.management.service.exceptions.MessageEmptyException;
import io.gravitee.management.service.exceptions.MessageRecipientFormatException;
import io.gravitee.management.service.exceptions.TechnicalManagementException;
import io.gravitee.management.service.notification.ApiHook;
import io.gravitee.management.service.notification.Hook;
import io.gravitee.management.service.notification.PortalHook;
import io.gravitee.repository.exceptions.TechnicalException;
import io.gravitee.repository.management.api.ApiRepository;
import io.gravitee.repository.management.api.MembershipRepository;
import io.gravitee.repository.management.api.SubscriptionRepository;
import io.gravitee.repository.management.api.search.SubscriptionCriteria;
import io.gravitee.repository.management.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.ui.freemarker.FreeMarkerTemplateUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static io.gravitee.management.service.impl.MessageServiceImpl.MessageEvent.MESSAGE_SENT;

/**
 * @author Nicolas GERAUD (nicolas.geraud at graviteesource.com)
 * @author GraviteeSource Team
 */
@Component
public class MessageServiceImpl extends AbstractService implements MessageService {

    private final Logger LOGGER = LoggerFactory.getLogger(MessageServiceImpl.class);

    @Autowired
    ApiRepository apiRepository;

    @Autowired
    MembershipRepository membershipRepository;

    @Autowired
    SubscriptionRepository subscriptionRepository;

    @Autowired
    PortalNotificationService portalNotificationService;

    @Autowired
    UserService userService;

    @Autowired
    AuditService auditService;

    @Autowired
    EmailService emailService;

    @Autowired
    ApiService apiService;

    @Autowired
    private Configuration freemarkerConfiguration;

    @Autowired
    HttpClientService httpClientService;

    @Value("${email.from}")
    private String defaultFrom;

    public enum MessageEvent implements Audit.AuditEvent {
        MESSAGE_SENT
    }

    @Override
    public int create(String apiId, MessageEntity message) {
        assertMessageNotEmpty(message);
        try {
            Optional<Api> optionalApi = apiRepository.findById(apiId);
            if (!optionalApi.isPresent()) {
                throw new ApiNotFoundException(apiId);
            }
            Api api = optionalApi.get();

            int msgSize = send(api, message, getRecipientsId(api, message));

            auditService.createApiAuditLog(
                    apiId,
                    Collections.emptyMap(),
                    MESSAGE_SENT,
                    new Date(),
                    null,
                    message);
            return msgSize;
        } catch(TechnicalException ex) {
            LOGGER.error("An error occurs while trying to get create a message", ex);
            throw new TechnicalManagementException("An error occurs while trying to create a message", ex);
        }
    }

    @Override
    public int create(MessageEntity message) {
        assertMessageNotEmpty(message);

        int msgSize = send(null, message, getRecipientsId(message));

        auditService.createPortalAuditLog(
                Collections.emptyMap(),
                MESSAGE_SENT,
                getAuthenticatedUsername(),
                new Date(),
                null,
                message);
        return msgSize;
    }

    private int send(Api api, MessageEntity message, Set<String> recipientsId) {
        switch (message.getChannel()) {
            case MAIL:
                Set<String> mails = getRecipientsEmails(recipientsId);
                    if (!mails.isEmpty()) {
                        emailService.sendAsyncEmailNotification(new EmailNotificationBuilder()
                                .to(defaultFrom)
                                .bcc(mails.toArray(new String[0]))
                                .subject(message.getTitle())
                                .template(EmailNotificationBuilder.EmailTemplate.GENERIC_MESSAGE)
                                .params(Collections.singletonMap("message", message.getText()))
                                .build());
                }
                return mails.size();

            case PORTAL:
                Hook hook = api==null ? PortalHook.MESSAGE : ApiHook.MESSAGE;
                portalNotificationService.create(hook, new ArrayList<>(recipientsId), getPortalParams(api, message));
                return recipientsId.size();

            case HTTP:
                httpClientService.request(
                        HttpMethod.POST,
                        recipientsId.iterator().next(),
                        message.getParams(),
                        getPostMessage(api, message),
                        Boolean.valueOf(message.isUseSystemProxy()));
                return 1;
            default:
                return 0;
        }
    }

    @Override
    public Set<String> getRecipientsId(MessageEntity message) {
        if (MessageChannel.HTTP.equals(message.getChannel())) {
            return Collections.singleton(message.getRecipient().getUrl());
        }
        return getRecipientsId(null, message);
    }

    @Override
    public Set<String> getRecipientsId(Api api, MessageEntity message) {
        if (message != null && MessageChannel.HTTP.equals(message.getChannel())) {
            return Collections.singleton(message.getRecipient().getUrl());
        }
        assertRecipientsNotEmpty(message);
        MessageRecipientEntity recipientEntity = message.getRecipient();
        // 2 cases are implemented :
        // - global sending (no apiId provided) + scope MANAGEMENT
        // - api consumer (apiId provided) + scope APPLICATION
        // the first 2 cases are for admin communication, the last one for the api publisher communication.

        try {
            final Set<String> recipientIds = new HashSet<>();
            // CASE 1 : global sending
            if (api == null && RoleScope.MANAGEMENT.name().equals(recipientEntity.getRoleScope())) {
                for (String roleName: recipientEntity.getRoleValues()) {
                    recipientIds.addAll(
                            membershipRepository.findByRole(RoleScope.MANAGEMENT, roleName)
                                    .stream()
                                    .map(Membership::getUserId)
                                    .collect(Collectors.toSet()));
                }
            }
            // CASE 2 : specific api consumers
            else if (api != null && RoleScope.APPLICATION.name().equals(recipientEntity.getRoleScope())) {

                // Get apps allowed to consume the api
                List<String> applicationIds = subscriptionRepository.search(
                        new SubscriptionCriteria.Builder()
                                .apis(Collections.singleton(api.getId()))
                                .status(Subscription.Status.ACCEPTED)
                                .build())
                        .stream()
                        .map(Subscription::getApplication)
                        .collect(Collectors.toList());

                // Get members of the applications (direct members)
                for (String roleName: recipientEntity.getRoleValues()) {
                    recipientIds.addAll(
                            membershipRepository.findByReferencesAndRole(
                                    MembershipReferenceType.APPLICATION,
                                    applicationIds,
                                    RoleScope.APPLICATION,
                                    roleName)
                                    .stream()
                                    .map(Membership::getUserId)
                                    .collect(Collectors.toSet()));
                }
                // Get members of the applications (group members)
                if (api.getGroups() != null && !api.getGroups().isEmpty()) {
                    for (String roleName: recipientEntity.getRoleValues()) {
                        recipientIds.addAll(
                                membershipRepository.findByReferencesAndRole(
                                        MembershipReferenceType.GROUP,
                                        new ArrayList<>(api.getGroups()),
                                        RoleScope.APPLICATION,
                                        roleName)
                                        .stream()
                                        .map(Membership::getUserId)
                                        .collect(Collectors.toSet()));
                    }
                }
            }
            return recipientIds;
        } catch(TechnicalException ex) {
            LOGGER.error("An error occurs while trying to get recipients", ex);
            throw new TechnicalManagementException("An error occurs while trying to get recipients", ex);
        }
    }

    private Set<String> getRecipientsEmails(Set<String> recipientsId) {
        if(recipientsId.isEmpty()) {
            return Collections.emptySet();
        }

        Set<String> emails = userService.findByIds(new ArrayList<>(recipientsId))
                .stream()
                .filter(userEntity -> !StringUtils.isEmpty(userEntity.getEmail()))
                .map(UserEntity::getEmail)
                .collect(Collectors.toSet());
       return emails;
    }

    private void assertMessageNotEmpty(MessageEntity messageEntity) {
        if (    messageEntity == null ||
                (StringUtils.isEmpty(messageEntity.getTitle()) && StringUtils.isEmpty(messageEntity.getText()))) {
            throw new MessageEmptyException();
        }
    }

    private void assertRecipientsNotEmpty(MessageEntity messageEntity) {
        if (    messageEntity == null ||
                messageEntity.getRecipient() == null ||
                messageEntity.getChannel() == null ||
                messageEntity.getRecipient().getRoleScope() == null ||
                messageEntity.getRecipient().getRoleValues() == null ||
                messageEntity.getRecipient().getRoleValues().isEmpty()) {
            throw new MessageRecipientFormatException();
        }
    }

    private Map<String, Object> getPortalParams(Api api, MessageEntity message) {
        Map<String, Object> params = new HashMap<>();
        params.put("title", message.getTitle());
        params.put("message", message.getText());
        if (api != null) {
            Api paramApi = new Api();
            paramApi.setId(api.getId());
            paramApi.setName(api.getName());
            paramApi.setVersion(api.getVersion());
            params.put("api", paramApi);
        }
        return params;
    }

    private String getPostMessage(Api api, MessageEntity message) {
        if (message.getText() == null || api == null) {
            return message.getText();
        }
        try {
            Template template = new Template(new Date().toString(), message.getText(), freemarkerConfiguration);

            ApiModelEntity apiEntity = apiService.findByIdForTemplates(api.getId());
            Map<String, Object> model = new HashMap<>();
            model.put("api", apiEntity);

            return FreeMarkerTemplateUtils.processTemplateIntoString(template, model);
        } catch (IOException | TemplateException e) {
            LOGGER.error("Unable to apply templating on the message", e);
            throw new TechnicalManagementException("Unable to apply templating on the message", e);
        }
    }
}
