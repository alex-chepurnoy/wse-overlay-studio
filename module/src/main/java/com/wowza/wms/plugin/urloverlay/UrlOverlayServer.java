/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay;

import com.wowza.wms.logging.WMSLogger;
import com.wowza.wms.logging.WMSLoggerFactory;
import com.wowza.wms.application.WMSProperties;
import com.wowza.wms.plugin.urloverlay.egress.EgressRules;
import com.wowza.wms.plugin.urloverlay.rest.AppModuleConfig;
import com.wowza.wms.plugin.urloverlay.rest.AppStateConfig;
import com.wowza.wms.plugin.urloverlay.rest.CaptureConfig;
import com.wowza.wms.plugin.urloverlay.rest.CaptureLimitConfig;
import com.wowza.wms.plugin.urloverlay.rest.InfoConfig;
import com.wowza.wms.plugin.urloverlay.rest.ObjectConfig;
import com.wowza.wms.plugin.urloverlay.rest.ObjectsConfig;
import com.wowza.wms.plugin.urloverlay.rest.OverlayConfig;
import com.wowza.wms.plugin.urloverlay.rest.PreviewConfig;
import com.wowza.wms.plugin.urloverlay.rest.StatusConfig;
import com.wowza.wms.plugin.urloverlay.rest.TemplateAssignConfig;
import com.wowza.wms.plugin.urloverlay.rest.TemplateStarterConfig;
import com.wowza.wms.plugin.urloverlay.rest.TemplateStartersConfig;
import com.wowza.wms.plugin.urloverlay.rest.TemplateValidateConfig;
import com.wowza.wms.plugin.urloverlay.rest.OverlayAppsConfig;
import com.wowza.wms.plugin.urloverlay.rest.OverlaysConfig;
import com.wowza.wms.plugin.urloverlay.rest.NamedOverlayConfig;
import com.wowza.wms.plugin.urloverlay.rest.NamedOverlayPreviewConfig;
import com.wowza.wms.plugin.urloverlay.rest.NamedOverlayCaptureConfig;
import com.wowza.wms.plugin.urloverlay.rest.TemplateOverlayConfig;
import com.wowza.wms.plugin.urloverlay.rest.TemplateCopiesConfig;
import com.wowza.wms.plugin.urloverlay.rest.AppStreamsConfig;
import com.wowza.wms.plugin.urloverlay.rest.TemplateSpecConfig;
import com.wowza.wms.plugin.urloverlay.rest.TemplateUseConfig;
import com.wowza.wms.plugin.urloverlay.rest.ImagesConfig;
import com.wowza.wms.plugin.urloverlay.rest.SnapshotCache;
import com.wowza.wms.plugin.urloverlay.rest.SnapshotConfig;
import com.wowza.wms.plugin.urloverlay.rest.StreamRestartConfig;
import com.wowza.wms.plugin.urloverlay.rest.TemplateOrderConfig;
import com.wowza.wms.plugin.urloverlay.rest.TemplatesBuildConfig;
import com.wowza.wms.server.IServer;
import com.wowza.wms.server.IServerNotify2;

public class UrlOverlayServer implements IServerNotify2
{
	/** Every REST resource this module registers. */
	public static final Class<?>[] REST_RESOURCES = { OverlayConfig.class, ObjectConfig.class, ObjectsConfig.class, StatusConfig.class,
			CaptureConfig.class, PreviewConfig.class, InfoConfig.class, OverlayAppsConfig.class, TemplateStartersConfig.class,
			TemplateStarterConfig.class, TemplateValidateConfig.class, TemplateAssignConfig.class, OverlaysConfig.class,
			NamedOverlayConfig.class, NamedOverlayCaptureConfig.class, TemplateOverlayConfig.class, TemplateCopiesConfig.class, AppStreamsConfig.class,
			AppStateConfig.class, NamedOverlayPreviewConfig.class, AppModuleConfig.class, TemplatesBuildConfig.class, TemplateSpecConfig.class,
			TemplateUseConfig.class, ImagesConfig.class, StreamRestartConfig.class, TemplateOrderConfig.class, SnapshotConfig.class,
			CaptureLimitConfig.class };

	public static final Class<UrlOverlayServer> CLASS = UrlOverlayServer.class;
	public static String MODULE_NAME = CLASS.getSimpleName();

	private static volatile String accessMode = "authorized";

	public static String accessMode()
	{
		return accessMode;
	}

	@Override
	public void onServerCreate(IServer server)
	{
	}

	@Override
	public void onServerInit(IServer server)
	{
		WMSLogger log = WMSLoggerFactory.getLogger(null);
		WMSProperties props = server.getProperties();
		String access = props.getPropertyStr("UrlOverlayServer", "authorized");  //unauthorized | authorized | disabled
		accessMode = access;
		// Chrome runs in the capture service; WSE only asks it for frames, with its own egress rules
		EgressRules egress = new EgressRules(props.getPropertyBoolean(EgressRules.PROP_ALLOW_PRIVATE, false),
				props.getPropertyStr(EgressRules.PROP_ALLOW_HOSTS, null), props.getPropertyStr(EgressRules.PROP_BLOCK_HOSTS, null),
				props.getPropertyBoolean(EgressRules.PROP_BLOCK_CHROME_SERVICES, true), props.getPropertyStr(EgressRules.PROP_ALLOW_PORTS, null));
		SnapshotCache.configure(props.getPropertyInt(SnapshotCache.PROP_MAX_DECODES, SnapshotCache.DEFAULT_MAX_DECODES),
				props.getPropertyLong(SnapshotCache.PROP_TIMEOUT, SnapshotCache.DEFAULT_TIMEOUT_MS));
		CaptureClient client = CaptureClient.configure(props.getPropertyStr(CaptureClient.PROP_SOCKET, null), egress);
		try
		{
			for(Class<?> c: REST_RESOURCES)
			{
				if(access.equals("unauthorized"))
				{
					server.addUnAuthorizedRESTAPI(c);
				}
				else if (access.equals("authorized"))
				{
					server.addAuthorizedRESTAPI(c);
				}
			}
		}
		catch (Exception ex)
		{
			log.error(ex);
		}
		if(access.equals("unauthorized"))
		{
			log.warn("UrlOverlayServer: REST API is registered without authentication (UrlOverlayServer=unauthorized)");
		}
		log.info("UrlOverlayServer " + ReleaseInfo.getVersion() + " RestAPI Access:" + access + " capture service socket:" + client.socket());
	}

	@Override
	public void onServerShutdownStart(IServer server)
	{
	}

	@Override
	public void onServerShutdownComplete(IServer server)
	{
	}

	@Override
	public void onServerConfigLoaded(IServer server)
	{
	}

}
