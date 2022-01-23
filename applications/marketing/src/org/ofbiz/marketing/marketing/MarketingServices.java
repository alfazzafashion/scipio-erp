/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.ofbiz.marketing.marketing;

import java.sql.Timestamp;
import java.util.Locale;
import java.util.Map;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilDateTime;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.util.EntityQuery;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;

/**
 * MarketingServices contains static service methods for Marketing Campaigns and Contact Lists.
 * See the documentation in marketing/servicedef/services.xml and use the service reference in
 * webtools.  Comments in this file are implemntation notes and technical details.
 */
public class MarketingServices {

    private static final Debug.OfbizLogger module = Debug.getOfbizLogger(java.lang.invoke.MethodHandles.lookup().lookupClass());
    public static final String resourceMarketing = "MarketingUiLabels";
    public static final String resourceOrder = "OrderUiLabels";

    public static Map<String, Object> signUpForContactList(DispatchContext dctx, Map<String, ? extends Object> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");

        Timestamp fromDate = UtilDateTime.nowTimestamp();
        String contactListId = (String) context.get("contactListId");
        String email = (String) context.get("email");
        String partyId = (String) context.get("partyId");

        if (!UtilValidate.isEmail(email)) {
            String error = UtilProperties.getMessage(resourceMarketing, "MarketingCampaignInvalidEmailInput", locale);
            return ServiceUtil.returnError(error);
        }

        try {
            // locate the contact list
            GenericValue contactList = EntityQuery.use(delegator).from("ContactList").where("contactListId", contactListId).queryOne();
            if (contactList == null) {
                String error = UtilProperties.getMessage(resourceMarketing, "MarketingContactListNotFound", UtilMisc.<String, Object>toMap("contactListId", contactListId), locale);
                return ServiceUtil.returnError(error);
            }

            // perform actions as the system user
            GenericValue userLogin = EntityQuery.use(delegator).from("UserLogin").where("userLoginId", "system").cache().queryOne();

            // associate the email with anonymous user TODO: do we need a custom contact mech purpose type, say MARKETING_EMAIL?
            if (partyId == null) {
                // Check existing email
                GenericValue contact = EntityQuery.use(delegator).from("PartyContactDetailByPurpose")
                        .where("infoString", email,
                                "contactMechTypeId", "EMAIL_ADDRESS",
                                "contactMechPurposeTypeId", "PRIMARY_EMAIL")
                        .orderBy("-fromDate")
                        .filterByDate("fromDate", "thruDate", "purposeFromDate", "purposeThruDate")
                        .queryFirst();
                if (contact != null) {
                    partyId = contact.getString("partyId");
                } else {
                    partyId = "_NA_";
                }
            }
            Map<String, Object> input = UtilMisc.toMap("userLogin", userLogin, "emailAddress", email, "partyId", partyId, "fromDate", fromDate, "contactMechPurposeTypeId", "OTHER_EMAIL");
            Map<String, Object> serviceResults = dispatcher.runSync("createPartyEmailAddress", input);
            if (ServiceUtil.isError(serviceResults)) {
                throw new GenericServiceException(ServiceUtil.getErrorMessage(serviceResults));
            }
            String contactMechId = (String) serviceResults.get("contactMechId");
            // create a new association at this fromDate to the anonymous party with status accepted
            input = UtilMisc.toMap("userLogin", userLogin, "contactListId", contactList.get("contactListId"),
                    "partyId", partyId, "fromDate", fromDate, "statusId", "CLPT_PENDING", "preferredContactMechId", contactMechId, "baseLocation", context.get("baseLocation"));
            serviceResults = dispatcher.runSync("createContactListParty", input);
            if (ServiceUtil.isError(serviceResults)) {
                throw new GenericServiceException(ServiceUtil.getErrorMessage(serviceResults));
            }
        } catch (GenericEntityException e) {
            String error = UtilProperties.getMessage(resourceOrder, "checkhelper.problems_reading_database", locale);
            Debug.logInfo(e, error + e.getMessage(), module);
            return ServiceUtil.returnError(error);
        } catch (GenericServiceException e) {
            String error = UtilProperties.getMessage(resourceMarketing, "MarketingServiceError", locale);
            Debug.logInfo(e, error + e.getMessage(), module);
            return ServiceUtil.returnError(error);
        }
        return ServiceUtil.returnSuccess();
    }
}
