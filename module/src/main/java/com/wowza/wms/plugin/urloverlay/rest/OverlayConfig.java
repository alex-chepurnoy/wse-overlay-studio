/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.rest;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import org.restlet.resource.ResourceException;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import com.wowza.wms.module.ModuleBase;
import com.wowza.wms.plugin.urloverlay.OverlayImage;
import com.wowza.wms.plugin.urloverlay.UrlOverlayLog;
import com.wowza.wms.plugin.urloverlay.UrlOverlayModule;
import com.wowza.wms.plugin.urloverlay.templates.ModuleFiles;
import com.wowza.wms.plugin.urloverlay.rest.WMSResponse2;
import com.wowza.wms.rest.WMSConfig;
import com.wowza.wms.rest.WMSResponse;
import com.wowza.wms.rest.vhosts.VHostsConfig;

@JsonInclude(Include.NON_NULL)
@JsonSerialize
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.NONE, getterVisibility = JsonAutoDetect.Visibility.NONE, isGetterVisibility = JsonAutoDetect.Visibility.NONE, setterVisibility = JsonAutoDetect.Visibility.NONE, creatorVisibility = JsonAutoDetect.Visibility.NONE)
@JacksonXmlRootElement(localName = "overlay")

@WMSConfig(restLocation = "/v1/{server}/plugin/urloverlay/vhosts/{vhostName}/applications/{appName}/instances/{instance}/streams/{stream}/overlay", restVerbs = "GET,POST,PUT,DELETE", xmlFilename = "")
public class OverlayConfig extends JsonApplicationConfigBase
{
	// wse-plugin-overlays and VIF draw in slot 1; a separate slot keeps the two overlays from replacing each other.
	public static final int DEFAULT_OVERLAY_INDEX = 16;

	@JsonProperty("vhostName")
	protected String vhostName = null;

	@JsonProperty("appName")
	protected String appName = null;

	@JsonProperty("applicationInstance")
	protected String instanceName = null;

	@JsonProperty("streamName")
	protected String streamName = null;

	@JsonProperty("overlaySource")
	protected boolean overlaySource = true;

	@JsonProperty("overlayEncodes")
	protected ArrayList<String> overlayEncodes = new ArrayList<String>();

	@JsonProperty("opacity")
	protected int opacity = -1;

	@JsonProperty("overlayIndex")
	// 0 means the application's UrlOverlayIndex (default DEFAULT_OVERLAY_INDEX)
	protected int overlayIndex = 0;

	@JsonProperty("refreshRate")
	protected double refreshRate = 0.0;

	@JsonProperty("threadDrawing")
	protected boolean threadDrawing = true;

	@JsonProperty("maxDrawingTime")
	protected int maxDrawingTime = 0;

	@JsonProperty("x")
	protected int x = 0;

	@JsonProperty("y")
	protected int y = 0;

	@JsonProperty("w")
	protected int w = -1;

	@JsonProperty("h")
	protected int h = -1;

	@JsonProperty("even_coords")
	protected boolean evenCoords;

	//DEFAULTS
	@JsonProperty("stroke")
	protected int stroke = 1;

	@JsonProperty("color")
	protected String color = "black";

	@JsonProperty("fillColor")
	protected String fillColor = null;

	@JsonProperty("shadowColor")
	protected String shadowColor = null;

	@JsonProperty("shadowOffset")
	protected int shadowOffset = 2;

	//Java defines five logical font families that are Serif, SansSerif, Monospaced, Dialog, and DialogInput.
	@JsonProperty("font")
	protected String font = "Serif";

	@JsonProperty("fontStyle")
	protected String fontStyle = "plain";

	@JsonProperty("fontSize")
	protected int fontSize = 16;
	
	@JsonProperty("objects")
	protected List<ObjectConfig> objects = new ArrayList<ObjectConfig>();

	@JsonIgnore
	private UUID uuid = UUID.randomUUID();

	// set when this overlay came from a named overlay through the stream's template; such an overlay is not saved per stream
	@JsonIgnore
	private String namedOverlay = null;

	@JsonIgnore
	protected BufferedImage backgroundImage = null;

	@JsonIgnore
	protected HashMap<Double, BufferedImage> backgroundImageCache = new HashMap<>();

	public OverlayConfig()
	{
		addFieldForKeyMap("vhostName", "vhostName");
		addFieldForKeyMap("appName", "appName");
		addFieldForKeyMap("instance", "instanceName");
		addFieldForKeyMap("stream", "streamName");
		setDefaults();
	}

	/**
	 * Reads a per-stream overlay file and binds it to the stream it was found for. The file's own context fields,
	 * if any, are ignored: a hand-written or copied file without them would otherwise reach getModule with a null
	 * stream name and fail.
	 */
	public static OverlayConfig loadStreamFile(File file, String vhostName, String appName, String instanceName, String streamName) throws IOException
	{
		OverlayConfig config = new ObjectMapper().readValue(file, OverlayConfig.class);
		config.vhostName = vhostName;
		config.appName = appName;
		config.instanceName = instanceName;
		config.streamName = streamName;
		return config;
	}

	@JsonIgnore
	public String getNamedOverlay() { return namedOverlay; }
	@JsonIgnore
	public void setNamedOverlay(String name) { this.namedOverlay = name; }

	@JsonIgnore
	public String getBoundVHostName() { return vhostName; }
	@JsonIgnore
	public String getBoundAppName() { return appName; }
	@JsonIgnore
	public String getBoundInstanceName() { return instanceName; }
	@JsonIgnore
	public String getBoundStreamName() { return streamName; }

	public OverlayConfig(String vhostName, String appName, String instanceName, String streamName)
	{
		super(vhostName, appName);
		this.vhostName = vhostName;
		this.appName = appName;
		this.instanceName = instanceName;
		this.streamName = streamName;
		addFieldForKeyMap("vhostName", "vhostName");
		addToStringKeyMap("vhostName", vhostName);
		addFieldForKeyMap("appName", "appName");
		addToStringKeyMap("appName", appName);
		addFieldForKeyMap("instance", "instanceName");
		addToStringKeyMap("instance", instanceName);
		addFieldForKeyMap("stream", "streamName");
		addToStringKeyMap("stream", streamName);
		setDefaults();
	}

	public OverlayConfig(UUID uuid)
	{
		setDefaults();
		this.uuid = uuid;
	}


	public boolean getOverlaySource() { return this.overlaySource; };
	public void setOverlaySource(boolean overlaySource) { this.overlaySource = overlaySource; };

	public boolean getThreadDrawing() { return this.threadDrawing; };
	public void setThreadDrawing(boolean threadDrawing) {this.threadDrawing = threadDrawing; };

	public ArrayList<String> getOverlayEncodes() { return this.overlayEncodes; };
	public void setOverlayEncodes(ArrayList<String> overlayEncodes) { this.overlayEncodes = overlayEncodes; };

	public int getX() { return x; };
	public void setX(int x) { this.x = x; };

	public int getY() { return y; };
	public void setY(int y) { this.y = y; };

	public int getW() { return w; };
	public void setW(int w) { this.w = w; };

	public int getH() { return h; };
	public void setH(int h) { this.h = h; };

	public boolean getEvenCoords() { return evenCoords; };
	public void setEvenCoords(Boolean evenCoords) { this.evenCoords = evenCoords; };

	public int getOpacity() { return opacity; };
	public void setOpacity(int opacity) { this.opacity = opacity; };

	public int getOverlayIndex() { return overlayIndex; };

	public double getRefreshRate() { return refreshRate; };
	public void setRefreshRate(double refreshRate) { this.refreshRate = refreshRate; };

	public List<ObjectConfig> getObjects() { return objects; };
	public void setObjects(List<ObjectConfig> objects) { this.objects = objects; };

	public BufferedImage getBackgroundImage(double scaled)
	{
		if(backgroundImage == null)
			return null;
		if(scaled == 1.0)
			return backgroundImage;

		if(!backgroundImageCache.containsKey(scaled))
		{
			int newWidth = (int)(backgroundImage.getWidth() * scaled);
			int newHeight = (int)(backgroundImage.getHeight() * scaled);
			BufferedImage resizedImage = new BufferedImage(newWidth, newHeight, backgroundImage.getType());
			Graphics2D g = resizedImage.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_DEFAULT);
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.drawImage(backgroundImage, 0, 0, newWidth, newHeight, null);
			g.dispose();
			backgroundImageCache.put(scaled,resizedImage);
		}
		return backgroundImageCache.get(scaled);
	};
	public void setBackgroundImage(BufferedImage backgroundImage) { this.backgroundImage = backgroundImage; };

	@Override
	protected void doInit() throws ResourceException
	{
		super.doInit();
		addToStringKeyMapFromRequest("vhostName");
		addToStringKeyMapFromRequest("appName");
		addToStringKeyMapFromRequest("instance");
		addToStringKeyMapFromRequest("stream");
		setPathMap();
	}

	public final void setDefaults()
	{
		vhostName = null;
		appName = null;
		instanceName = null;
		streamName = null;
		overlaySource = true;
		overlayEncodes.clear();
		opacity = -1;
		overlayIndex = 0;
		refreshRate = 0.0;
		threadDrawing = true;
		maxDrawingTime = 0;
		x = 0;
		y = 0;
		w = -1;
		h = -1;
		evenCoords = false;
		stroke = 1;
		color = "black";
		fillColor = null;
		shadowColor = null;
		shadowOffset = 2;
		font = "Serif";
		fontStyle = "plain";
		fontSize = 16;
		objects.clear();
		uuid = UUID.randomUUID();
		backgroundImage = null;
		backgroundImageCache.clear();
	}

	public String getVhostName() {return vhostName;};
	public String getAppName() {return appName;};

	public String getInstanceName()
	{
		return this.instanceName;
	}

	public void setInstanceName(String instanceName)
	{
		this.instanceName = instanceName;
	}

	public String getStreamName()
	{
		return this.streamName;
	}

	public UUID getUuid()
	{
		return uuid;
	}

	public void resetUuid()
	{
		uuid = UUID.randomUUID();
	}

	/** Whether any web page on this overlay has a frame that has not been drawn yet. */
	public boolean hasNewFrame()
	{
		List<ObjectConfig> list = objects;
		if(list != null)
		{
			for(ObjectConfig o: list)
			{
				if(o.hasNewFrame())
				{
					return true;
				}
			}
		}
		return false;
	}

	@Override
	protected boolean exists(String restVerb)
	{
		return !restVerb.equals("post");
	}

	//this is the GET
	@Override
	public boolean loadObject()
	{
		if(namesProblem() != null)
		{
			return false;
		}
		UrlOverlayModule overlayModule = (UrlOverlayModule)getModule(vhostName, appName, instanceName, streamName, new WMSResponse());

		if(overlayModule == null)
		{
			return false;
		}
		OverlayImage img = overlayModule.getOverlayImage(streamName);
		if(img != null)
		{
			this.copyFrom(img.getOverlayConfig());
		}
		else
		{
			// not publishing: show the saved layers, which load when the stream's transcoder starts
			File saved = new File(getFilename());
			if(saved.isFile())
			{
				try
				{
					this.copyFrom(loadStreamFile(saved, vhostName, appName, instanceName, streamName));
				}
				catch(IOException | RuntimeException e)
				{
					logWarn("UrlOverlay: could not read " + saved.getName() + ": " + e);
				}
			}
		}
		return true;
	}

	//this is the POST
	@Override
	public WMSResponse saveNewObject() throws ConfigBaseException
	{
		checkNames();
		WMSResponse2 retVal = new WMSResponse2();
		UrlOverlayModule overlayModule = (UrlOverlayModule)getModule(vhostName, appName, instanceName, streamName, retVal);
		if(overlayModule == null)
		{
			ConfigBaseException ex =new ConfigBaseException();
			ex.addError("","Overlay Module not found");
			throw ex;
		}
		if(this.w > 0)
		{
			overlayModule.setStreamWidth(streamName, this.w);
		}
		if(this.h > 0)
		{
			overlayModule.setStreamHeight(streamName, this.h);
		}
		OverlayImage newOverlay = new OverlayImage(this.x ,this.y, overlayModule.getStreamWidth(streamName), overlayModule.getStreamHeight(streamName), this, threadDrawing, maxDrawingTime);
		newOverlay.setEvenCoords(evenCoords);

		applyDefaults();
		if(objects != null)
		{
			for(ObjectConfig obj: objects)
			{
				obj.checkWebPageUrl();
			}
		}
		sortAndSaveObjects();
		newOverlay.setOverlayConfig(this);
		overlayModule.setOverlayImage(streamName, newOverlay);
		resetUuid();
		UrlOverlayLog.change(this, "set overlay on " + UrlOverlayLog.stream(vhostName, appName, instanceName, streamName) + " with "
				+ (objects == null ? 0 : objects.size()) + " object(s)");

		retVal.setSuccess();
		retVal.theObject = this;
		return retVal;
	}

	//this is the PUT
	@Override
	public WMSResponse saveObject() throws ConfigBaseException
	{
		checkNames();
		WMSResponse2 retVal = new WMSResponse2();
		UrlOverlayModule overlayModule = (UrlOverlayModule)getModule(vhostName, appName, instanceName, streamName, retVal);
		if(overlayModule == null)
		{
			ConfigBaseException ex =new ConfigBaseException();
			ex.addError("","Overlay Module not found");
			throw ex;
		}

		OverlayImage overlay = overlayModule.getOverlayImage(streamName);
		if(overlay == null)
		{
			ConfigBaseException ex =new ConfigBaseException();
			ex.addError("","Overlay not created yet");
			throw ex;
		}
		applyDefaults();
		overlay.mergeOverlayConfig(this);
		sortAndSaveObjects();
		UrlOverlayLog.change(this, "updated overlay on " + UrlOverlayLog.stream(vhostName, appName, instanceName, streamName));

		resetUuid();
		retVal.setSuccess();
		retVal.theObject = this;
		return retVal;
	}

	//this is the DELETE
	@Override
	public WMSResponse deleteObject()
	{
		WMSResponse2 retVal = new WMSResponse2();
		String bad = namesProblem();
		if(bad != null)
		{
			retVal.message = bad;
			return retVal;
		}
		UrlOverlayModule overlayModule = (UrlOverlayModule)getModule(vhostName, appName, instanceName, streamName, retVal);
		if(overlayModule == null)
		{
			retVal.message = "Overlay Module not found";
			return retVal;
		}

		overlayModule.clearOverlayImage(streamName);
		resetUuid();
		File f = new File(this.getFilename());
		f.delete();
		UrlOverlayLog.change(this, "deleted overlay on " + UrlOverlayLog.stream(vhostName, appName, instanceName, streamName));

		retVal.setSuccess();
		retVal.message = "Deleted";
		return retVal;
	}

	/** conf.modules/urloverlay/&lt;app&gt;/streams/&lt;instance&gt;/&lt;stream&gt;.json. */
	@Override
	public String getFilename() {
		String dir = vhostName == null ? null : VHostsConfig.getVHostDir(vhostName);
		if (dir == null || appName == null || instanceName == null || streamName == null) {
			return null;
		}
		return ModuleFiles.streamFile(java.nio.file.Paths.get(dir), appName.trim(), instanceName, streamName).toString();
	}

	/** Why the request's names cannot give this stream's file, or null when they can. */
	String namesProblem()
	{
		try
		{
			return getFilename() == null ? "No stream file for " + vhostName + "/" + appName + "/" + instanceName + "/" + streamName : null;
		}
		catch(ModuleFiles.BadName e)
		{
			return e.getMessage();
		}
	}

	private void checkNames() throws ConfigBaseException
	{
		String bad = namesProblem();
		if(bad != null)
		{
			ConfigBaseException ex = new ConfigBaseException();
			ex.addError("stream", bad);
			throw ex;
		}
	}

	protected WMSResponse saveObjectToFile()
	{
		return saveObjectToFile(this.getFilename());
	}

	@Override
	protected WMSResponse saveObjectToFile(String filename) {
		WMSResponse retVal = new WMSResponse();
		if(namedOverlay != null)
		{
			// the named overlay's own file is the record; a per-stream copy would shadow later edits
			return retVal;
		}
		try {
			ObjectMapper mapper = new ObjectMapper();
			ObjectWriter writer = mapper.writer(new DefaultPrettyPrinter());
			File out = new File(getFilename());
			out.getParentFile().mkdirs();
			writer.writeValue(out, this);

		} catch (IOException var9) {
			retVal.message = var9.getMessage();
			this.logException("saveObjectToFile", var9);
		}
		return retVal;
	}

	public static ModuleBase getModule(String vHostName, String appName, String instanceName, String streamName, WMSResponse retVal )
	{
		return UrlOverlayModule.getModule(vHostName, appName, instanceName, streamName, why -> {
			retVal.message = why;
			logWarn("OverlayConfig:" + why);
		});
	}

	public void sortAndSaveObjects()
	{
		sortObjects(this.objects);
		saveObjectToFile();
	}

	public void sortObjects()
	{
		sortObjects(this.objects);
	}

	@SuppressWarnings("Convert2Lambda")
	public static void sortObjects(List<ObjectConfig> list)
	{
		Collections.sort(list, new Comparator<>()
		{
			@Override
			public int compare(ObjectConfig obj1, ObjectConfig obj2)
			{
				if(obj1.getzOrder() == obj2.getzOrder())
					if(obj1.getX() == obj2.getX())
						return obj1.getId().compareTo(obj2.getId());
					else
						return Integer.compare(obj1.getX(), obj2.getX());
				else
					return Integer.compare(obj1.getzOrder(), obj2.getzOrder());
			}
		});
	}

	public void addObject(ObjectConfig obj)
	{
		this.objects.add(obj);
		sortObjects();
	}

	public ObjectConfig getObject(String obectId)
	{
		ObjectConfig retVal =null;
		for (ObjectConfig obj : this.objects)
		{
			if(obj.getId().equals(obectId))
			{
				retVal = obj;
				break;
			}
		}
		return retVal;
	}

	public void deleteObject(String objectId)
	{
		int idx = 0;
		int deleteIdx = -1;
		for (ObjectConfig obj : this.objects)
		{
			if(obj.getId().equals(objectId))
			{
				deleteIdx = idx;
				break;
			}
			idx++;
		}
		if(deleteIdx > -1)
		{
			this.objects.remove(deleteIdx).dispose();
		}
		sortObjects();
	}

	public void applyDefaults()
	{
		if(this.objects != null)
		{
			for (ObjectConfig obj : this.objects)
			{
				obj.applyDefaults();
				obj.applyDefaults(this);
			}
		}
	}
}
