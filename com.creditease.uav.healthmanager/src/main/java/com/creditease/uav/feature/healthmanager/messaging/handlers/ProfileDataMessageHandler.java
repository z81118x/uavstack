/*-
 * <<
 * UAVStack
 * ==
 * Copyright (C) 2016 - 2017 UAVStack
 * ==
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
 * >>
 */

package com.creditease.uav.feature.healthmanager.messaging.handlers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.creditease.agent.ConfigurationManager;
import com.creditease.agent.helpers.JSONHelper;
import com.creditease.agent.monitor.api.MonitorDataFrame;
import com.creditease.agent.profile.api.IStandardProfileModelListener;
import com.creditease.agent.profile.api.StandardProfileModeler;
import com.creditease.agent.spi.ActionContext;
import com.creditease.agent.spi.IActionEngine;
import com.creditease.agent.spi.ISystemActionEngineMgr;
import com.creditease.uav.cache.api.CacheManager;
import com.creditease.uav.datastore.api.DataStoreMsg;
import com.creditease.uav.datastore.api.DataStoreProtocol;
import com.creditease.uav.feature.healthmanager.HealthManagerConstants;
import com.creditease.uav.feature.healthmanager.messaging.AbstractMessageHandler;
import com.creditease.uav.messaging.api.Message;

public class ProfileDataMessageHandler extends AbstractMessageHandler implements IStandardProfileModelListener {

    private CacheManager cm;

    private static List<Map<String, String>> profileMsgList = new ArrayList<>();

    static {
        // jarlib
        addProfileMsgList(HealthManagerConstants.STORE_KEY_PROFILEINFO_JARLIB, "jars", "lib");
        // dubboprovider
        addProfileMsgList(HealthManagerConstants.STORE_KEY_PROFILEINFO_DUBBOPROVIDER, "cpt",
                "com.alibaba.dubbo.config.spring.ServiceBean");
        // mscphttp
        addProfileMsgList(HealthManagerConstants.STORE_KEY_PROFILEINFO_MSCPHTTP, "cpt",
                "com.creditease.agent.spi.AbstractBaseHttpServComponent");
        // mscptimeworker
        addProfileMsgList(HealthManagerConstants.STORE_KEY_PROFILEINFO_MSCPTIMEWORKER, "cpt",
                "com.creditease.agent.spi.AbstractTimerWork");
        // filter
        addProfileMsgList(HealthManagerConstants.STORE_KEY_PROFILEINFO_FILTER, "cpt",
                "javax.servlet.annotation.WebFilter");
        // listener
        addProfileMsgList(HealthManagerConstants.STORE_KEY_PROFILEINFO_LISTENER, "cpt",
                "javax.servlet.annotation.WebListener");
        // servlet
        addProfileMsgList(HealthManagerConstants.STORE_KEY_PROFILEINFO_SERVLET, "cpt",
                "javax.servlet.annotation.WebServlet");
        // jaxws
        addProfileMsgList(HealthManagerConstants.STORE_KEY_PROFILEINFO_JAXWS, "cpt", "javax.jws.WebService");
        // jaxwsp
        addProfileMsgList(HealthManagerConstants.STORE_KEY_PROFILEINFO_JAXWSP, "cpt",
                "javax.xml.ws.WebServiceProvider");
        // jaxrs
        addProfileMsgList(HealthManagerConstants.STORE_KEY_PROFILEINFO_JAXRS, "cpt", "javax.ws.rs.Path");
        // springmvc
        addProfileMsgList(HealthManagerConstants.STORE_KEY_PROFILEINFO_SPRINGMVC, "cpt",
                "org.springframework.stereotype.Controller");
        // springmvcrest
        addProfileMsgList(HealthManagerConstants.STORE_KEY_PROFILEINFO_SPRINGMVCREST, "cpt",
                "org.springframework.web.bind.annotation.RestController");
        // struts2
        addProfileMsgList(HealthManagerConstants.STORE_KEY_PROFILEINFO_STRUTS2, "cpt",
                "com.opensymphony.xwork2.Action");
    }

    private static void addProfileMsgList(String key, String elemId, String instanceId) {

        Map<String, String> map = new HashMap<>();
        map.put("key", key);
        map.put("elemId", elemId);
        map.put("instanceId", instanceId);
        profileMsgList.add(map);
    }

    public ProfileDataMessageHandler() {
        /**
         * start Caching Process
         */
        cm = (CacheManager) ConfigurationManager.getInstance().getComponent("healthmanager", "HMCacheManager");
    }

    @Override
    public void handle(Message msg) {

        // push the latest profile data to cache
        List<String> newMDFs = pushLatestProfileDataToCacheCenter(msg.getParam(getMsgTypeName()));

        if (newMDFs.size() > 0) {
            // set the new MDFs into msg
            msg.setParam(getMsgTypeName(), JSONHelper.toString(newMDFs));
            // store profile data
            super.handle(msg);
        }
    }

    @Override
    public String getMsgTypeName() {

        return MonitorDataFrame.MessageType.Profile.toString();
    }

    @Override
    protected void preInsert(DataStoreMsg dsMsg) {

        // set target collection
        dsMsg.put(DataStoreProtocol.MONGO_COLLECTION_NAME, HealthManagerConstants.MONGO_COLLECTION_PROFILE);
    }

    /**
     * 推送最新的ProfileData到缓存中心
     * 
     * @param profileString
     */
    private List<String> pushLatestProfileDataToCacheCenter(String profileString) {

        /**
         * setup ProfileDataMessageHandler as IStandardProfileModelListener for StandardProfileModeler
         */
        ISystemActionEngineMgr engineMgr = (ISystemActionEngineMgr) ConfigurationManager.getInstance()
                .getComponent("Global", "ISystemActionEngineMgr");

        IActionEngine engine = engineMgr.getActionEngine("StandardProfileModelingEngine");

        StandardProfileModeler modeler = (StandardProfileModeler) ConfigurationManager.getInstance()
                .getComponent("healthmanager", "StandardProfileModeler");

        modeler.setListener(this);

        cm.beginBatch();

        List<String> monitorDataFrames = JSONHelper.toObjectArray(profileString, String.class);

        List<String> newMDFs = new ArrayList<String>();

        for (String mdfStr : monitorDataFrames) {

            MonitorDataFrame mdf = new MonitorDataFrame(mdfStr);

            ActionContext ac = new ActionContext();

            ac.putParam(MonitorDataFrame.class, mdf);
            ac.putParam("NewMDFList", newMDFs);
            ac.putParam("MDFString", mdfStr);

            engine.execute("StandardProfileModeler", ac);
        }

        cm.submitBatch();

        return newMDFs;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void onBeforeMDFModeling(ActionContext ac, MonitorDataFrame mdf) {

        List<String> newMDFs = (List<String>) ac.getParam("NewMDFList");

        String tag = mdf.getTag();

        /**
         * note: only tag==P means this MDF is the new MDF, otherwise this MDF is just for heartbeat
         */
        if (tag.equalsIgnoreCase("P")) {

            String mdfStr = (String) ac.getParam("MDFString");

            newMDFs.add(mdfStr);
        }
    }

    @SuppressWarnings("rawtypes")
    @Override
    public void onBeforeFrameModeling(ActionContext ac, MonitorDataFrame mdf, String frameId, List<Map> frameData) {

        // do nothing
    }

    @Override
    public void onAppProfileMetaCreate(ActionContext ac, MonitorDataFrame mdf, String appid, String appurl,
            String appgroup, Map<String, Object> appProfile) {

        // do nothing
    }

    @SuppressWarnings("rawtypes")
    @Override
    public void onAppClientProfileCreate(ActionContext ac, MonitorDataFrame mdf, String appid, String appurl,
            String appgroup, Map<String, Object> appProfile, List<Map> clients) {

        String fieldKey = "C@" + appgroup + "@" + appurl;
        String clientsStr = JSONHelper.toString(clients);

        cm.putHash(HealthManagerConstants.STORE_REGION_UAV, HealthManagerConstants.STORE_KEY_PROFILEINFO_APPCLIENT,
                fieldKey, clientsStr);
    }

    @Override
    public void onAppProfileCreate(ActionContext ac, MonitorDataFrame mdf, String appid, String appurl, String appgroup,
            Map<String, Object> appProfile) {

        /**
         * use appgroup + appurl as the cache key, then we can get profile data by appgroup
         */
        String fieldKey = appgroup + "@" + appurl;

        cm.putHash(HealthManagerConstants.STORE_REGION_UAV, HealthManagerConstants.STORE_KEY_PROFILEINFO, fieldKey,
                JSONHelper.toString(appProfile));

        /**
         * NOTE: 独立存储profile各组件详细信息，这样可以减少profile的数据量，提升加载速度，需要读取组件详细信息时再读取
         */

        for (Map<String, String> map : profileMsgList) {

            String elemId = map.get("elemId");
            String instanceId = map.get("instanceId");

            Map<String, Object> comps = mdf.getElemInstValues(appid, elemId, instanceId);

            if (comps != null && comps.size() > 0) {
                String key = map.get("key");
                String compStr = JSONHelper.toString(comps);

                cm.putHash(HealthManagerConstants.STORE_REGION_UAV, key, fieldKey, compStr);
            }
        }

    }

    @SuppressWarnings("rawtypes")
    @Override
    public void onAppIPLinkProfileCreate(ActionContext ac, MonitorDataFrame mdf, String appid, String appurl,
            String appgroup, Map<String, Object> appProfile, List<Map> iplinks) {

        Map<String, String> appIpLnkMap = new HashMap<String, String>();

        for (Map iplink : iplinks) {
            String iplnk_id = (String) iplink.get("id");
            @SuppressWarnings("unchecked")
            Map<String, Object> iplnk_values = (Map<String, Object>) iplink.get("values");
            appIpLnkMap.put(iplnk_id, JSONHelper.toString(iplnk_values));
            appIpLnkMap.put(iplnk_id + "-ts", String.valueOf(iplnk_values.get("ts")));
        }

        String iplnkfieldKey = "LNK@" + appgroup + "@" + appurl;

        cm.putHash(HealthManagerConstants.STORE_REGION_UAV, iplnkfieldKey, appIpLnkMap);

    }

    @SuppressWarnings("rawtypes")
    @Override
    public void onAfterFrameModeling(ActionContext ac, MonitorDataFrame mdf, String frameId, List<Map> frameData,
            Map<String, Object> appProfile) {

        // do nothing
    }

    @Override
    public void onAfterMDFModeling(ActionContext ac, MonitorDataFrame mdf) {

        // do nothing
    }
}
