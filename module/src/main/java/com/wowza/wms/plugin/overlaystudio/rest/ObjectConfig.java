/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Date;
import java.util.HashMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.restlet.resource.ResourceException;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import com.wowza.util.Base64;
import com.wowza.wms.plugin.overlaystudio.Fonts;
import com.wowza.wms.plugin.overlaystudio.TextTokens;
import com.wowza.wms.plugin.overlaystudio.OverlayImage;
import com.wowza.wms.plugin.overlaystudio.OverlayStudioLog;
import com.wowza.wms.plugin.overlaystudio.OverlayStudioModule;
import com.wowza.wms.plugin.overlaystudio.WebPageCapture;
import com.wowza.wms.plugin.overlaystudio.egress.AddressRules;
import com.wowza.wms.plugin.overlaystudio.CaptureClient;
import com.wowza.wms.plugin.overlaystudio.egress.EgressRules;
import com.wowza.wms.plugin.overlaystudio.rest.WMSResponse2;
import com.wowza.wms.rest.ConfigBase;
import com.wowza.wms.rest.WMSConfig;
import com.wowza.wms.rest.WMSResponse;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonSerialize
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.NONE, getterVisibility = JsonAutoDetect.Visibility.NONE, isGetterVisibility = JsonAutoDetect.Visibility.NONE, setterVisibility = JsonAutoDetect.Visibility.NONE, creatorVisibility = JsonAutoDetect.Visibility.NONE)
@JacksonXmlRootElement(localName = "Object")

@WMSConfig(restLocation = "/v1/{server}/plugin/overlaystudio/vhosts/{vhostName}/applications/{appName}/instances/{instance}/streams/{stream}/overlay/objects/{id}", restVerbs = "GET,PUT,POST,DELETE", xmlFilename = "")
public class ObjectConfig extends JsonApplicationConfigBase
{
	protected String instanceName = null;
	protected String streamName = null;
	public OverlayImage oImage = null;
	public DateTime created_at;
	public DateTime updated_at;

	// Shared by every draw thread, so synchronized, and bounded so dragging and scaling cannot grow them (D11). There
	// is no gradient cache: a GradientPaint holds its own start and end points, and one is cheap to build per draw.
	private static final Map<String, Font> fontCache = boundedCache();
	private static final Map<String, Color> colorCache = boundedCache();
	private static final Map<String, BasicStroke> basicStrokeCache = boundedCache();

	static final int CACHE_ENTRIES = 256;

	private static <V> Map<String, V> boundedCache()
	{
		return java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, V>(64, 0.75f, true)
		{
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, V> eldest)
			{
				return size() > CACHE_ENTRIES;
			}
		});
	}

	// Runtime state, transient so copyFrom() never overwrites it with a request object's empty state.
	private transient final Object webPageLock = new Object();
	private transient WebPageCapture webPageCapture = null;
	private transient boolean disposed = false;
	// one object per layer, so release() can take this layer's listener off a page other layers share
	private transient final Runnable reloadListener = this::onWebPageReloaded;

	@JsonProperty("id")
	protected String id = UUID.randomUUID().toString();;

	@JsonProperty("type")
	protected String type = null;

	@JsonProperty("zOrder")
	protected int zOrder = -1;

	// When the layer shows (3.3a): showFrom and hideAt are an ISO date-time with its offset, or stream+N, N seconds
	// after the stream's transcoder started; absent means always. blinkSeconds: on for N s, off for N s, by the wall
	// clock, so every output blinks together. fadeIn and fadeOut: 0 to 2 s at the start and end of the show window.
	@JsonProperty("showFrom")
	protected String showFrom = null;

	@JsonProperty("hideAt")
	protected String hideAt = null;

	@JsonProperty("blinkSeconds")
	protected Double blinkSeconds = null;

	@JsonProperty("fadeIn")
	protected Double fadeIn = null;

	@JsonProperty("fadeOut")
	protected Double fadeOut = null;

	@JsonProperty("x")
	protected int x = -1;

	@JsonProperty("y")
	protected int y = -1;

	@JsonProperty("x2")
	protected int x2 = -1;

	@JsonProperty("y2")
	protected int y2 = -1;

	@JsonProperty("x3")
	protected int x3 = -1;

	@JsonProperty("y3")
	protected int y3 = -1;

	@JsonProperty("w")
	protected int w = -1;

	@JsonProperty("h")
	protected int h = -1;

	@JsonProperty("opacity")
	protected int opacity = -1;

	@JsonProperty("arcWidth")
	protected int arcWidth = -1;

	@JsonProperty("arcHeight")
	protected int arcHeight = -1;

	@JsonProperty("stroke")
	protected int stroke = -1;

	@JsonProperty("color")
	protected String color = null;

	@JsonProperty("fillColor")
	protected String fillColor = null;

	@JsonProperty("gradientDirection")
	protected String gradientDirection = null;

	@JsonProperty("gradientColor")
	protected String gradientColor = null;

	@JsonProperty("base64Image")
	protected String base64Image = null;

	@JsonProperty("shadowColor")
	protected String shadowColor = null;

	@JsonProperty("shadowOffset")
	protected int shadowOffset = -1;

	//Java defines five logical font families that are Serif, SansSerif, Monospaced, Dialog, and DialogInput.
	@JsonProperty("font")
	protected String font = null;

	@JsonProperty("fontStyle")
	protected String fontStyle = null;

	@JsonProperty("fontSize")
	protected int fontSize = -1;

	@JsonProperty("text")
	protected String text = null;

	// Text layout (plan 3.2). box: x, y, w, h are a text box that wraps at w and clips at h; without it x, y is the
	// first line's baseline start. align inside the box: left, center or right. lineHeight: a multiple of the font
	// size, 1.2 when not set. background, padding and radius: a box behind the text (the whole w, h with box; the
	// text's extent plus padding without). outlineColor and outlineWidth: a stroke around the letters, 0 to 20 px.
	// autoFit: shrink the font, down to half, until the text fits the box.
	@JsonProperty("box")
	protected Boolean box = null;

	@JsonProperty("align")
	protected String align = null;

	@JsonProperty("lineHeight")
	protected Double lineHeight = null;

	@JsonProperty("background")
	protected String background = null;

	@JsonProperty("padding")
	protected Integer padding = null;

	@JsonProperty("radius")
	protected Integer radius = null;

	@JsonProperty("outlineColor")
	protected String outlineColor = null;

	@JsonProperty("outlineWidth")
	protected Double outlineWidth = null;

	@JsonProperty("autoFit")
	protected Boolean autoFit = null;

	// For date and time tokens (plan 3.3): the zone (IANA, the server's when not set), the locale (en-US when not
	// set), the target of a countdown or count-up (ISO date-time with its offset) and the text shown at and past it.
	@JsonProperty("timeZone")
	protected String timeZone = null;

	@JsonProperty("locale")
	protected String locale = null;

	@JsonProperty("target")
	protected String target = null;

	@JsonProperty("endText")
	protected String endText = null;

	@JsonProperty("url")
	protected String url = null;

	@JsonProperty("pageDelay")
	protected int pageDelay = -1;

	@JsonProperty("reloadRate")
	protected double reloadRate = -1;

	// browser viewport for a webPage; 0 means the object's w and h. The int fields below that a partial PUT can leave
	// out are boxed: null is "not given" and reads as 0 (num), so the merge can tell it from an explicit 0
	@JsonProperty("viewportWidth")
	protected Integer viewportWidth = null;

	@JsonProperty("viewportHeight")
	protected Integer viewportHeight = null;

	// color made transparent in a webPage capture; null means rgb(1,2,3)
	@JsonProperty("keyColor")
	protected String keyColor = null;

	// largest per-channel difference from keyColor that is still keyed out; 0 is an exact match
	@JsonProperty("keyTolerance")
	protected Integer keyTolerance = null;

	// paint the page body with keyColor after load; null means true
	@JsonProperty("paintBackground")
	protected Boolean paintBackground = null;

	// the part of a webPage capture that is drawn, in page pixels from its top left; cropW or cropH 0 draws all of it
	@JsonProperty("cropX")
	protected Integer cropX = null;

	@JsonProperty("cropY")
	protected Integer cropY = null;

	@JsonProperty("cropW")
	protected Integer cropW = null;

	@JsonProperty("cropH")
	protected Integer cropH = null;

	private transient volatile long lastKeyMs = -1;
	// The page frame this layer drew last, per output scale, so a refresh with no new frame does not redraw. Scales draw
	// in parallel, so one marker would let a scale that drew a newer frame hide it from another. A draw notes the frame
	// in pendingSeq and commitDrawn moves it to drawnSeq once that draw is published.
	private transient final java.util.concurrent.ConcurrentHashMap<Double, Long> pendingSeq = new java.util.concurrent.ConcurrentHashMap<>();
	private transient final java.util.concurrent.ConcurrentHashMap<Double, Long> drawnSeq = new java.util.concurrent.ConcurrentHashMap<>();
	private transient volatile long lastScaleMs = -1;
	// the keyed frame scaled to the size it is drawn at, made once per frame and size
	private transient volatile BufferedImage scaledFrom = null;
	private transient volatile BufferedImage scaledImage = null;
	// the last keyed frame this layer drew, for the stream capture endpoint
	private transient volatile BufferedImage lastKeyedImage = null;
	/** Time this thread spent waiting for page screenshots in draw(), so a draw's own cost is reported apart from it. */
	public static final ThreadLocal<Long> CAPTURE_WAIT_NS = ThreadLocal.withInitial(() -> 0L);

	public String getType() { return type; };
	public void setType(String type) { this.type = type; };

	public int getzOrder() { return zOrder; };
	public void setzOrder(int zOrder) { this.zOrder = zOrder; }

	public String getShowFrom() { return showFrom; }
	public void setShowFrom(String showFrom) { this.showFrom = showFrom; }

	public String getHideAt() { return hideAt; }
	public void setHideAt(String hideAt) { this.hideAt = hideAt; }

	public Double getBlinkSeconds() { return blinkSeconds; }
	public void setBlinkSeconds(Double blinkSeconds) { this.blinkSeconds = blinkSeconds; }

	public Double getFadeIn() { return fadeIn; }
	public void setFadeIn(Double fadeIn) { this.fadeIn = fadeIn; }

	public Double getFadeOut() { return fadeOut; }
	public void setFadeOut(Double fadeOut) { this.fadeOut = fadeOut; }

	public int getX() { return x; };
	public int getX(double scaled) { return (int)(ev(x)*scaled); };
	public void setX(int x) { this.x = x; };

	public int getY() { return y; };
	public int getY(double scaled) { return (int)(ev(y)*scaled); };
	public void setY(int y) { this.y = y; };

	public int getX2() { return x2; };
	public int getX2(double scaled) { return (int)(ev(x2)*scaled); };
	public void setX2(int x2) { this.x2 = x2; };

	public int getY2() { return y2; };
	public int getY2(double scaled) { return (int)(ev(y2)*scaled); };
	public void setY2(int y2) { this.y2 = y2; };

	public int getX3() { return x3; };
	public int getX3(double scaled) { return (int)(ev(x3)*scaled); };
	public void setX3(int x3) { this.x3 = x3; };

	public int getY3() { return y3; };
	public int getY3(double scaled) { return (int)(ev(y3)*scaled); };
	public void setY3(int y3) { this.y3 = y3; };

	public int getW() { return w; };
	public int getW(double scaled) { return (int)(ev(w)*scaled); };
	public void setW(int w) { this.w = w; };

	public int getH() { return h; };
	public int getH(double scaled) { return (int)(ev(h)*scaled); };
	public void setH(int h) { this.h = h; };

	public int getOpacity() { return opacity; }
	public void setOpacity(int opacity) { this.opacity = opacity; };

	public int getArcWidth() { return arcWidth; };
	public int getArcWidth(double scaled) { return (int)(arcWidth*scaled); };
	public void setArcWidth(int arcWidth) { this.arcWidth = arcWidth; };

	public int getArcHeight() { return arcHeight; };
	public int getArcHeight(double scaled) { return (int)(arcHeight*scaled); };
	public void setArcHeight(int arcHeight) { this.arcHeight = arcHeight; };


	public int getStroke() { return stroke; };
	public int getStroke(double scaled) { return Math.max((int)(stroke*scaled),1); };
	public void setStroke(int stroke) { this.stroke = stroke; };

	public String getColor() { return color; };
	public void setColor(String color) { this.color = color; };

	public String getFillColor() { return fillColor; }
	public void setFillColor(String fillColor) { this.fillColor = fillColor; }

	public String getShadowColor() { return shadowColor; };
	public void setShadowColor(String shadowColor) { this.shadowColor = shadowColor; };

	public int getShadowOffset() { return shadowOffset; };
	public int getShadowOffset(double scaled) { return (int)(shadowOffset*scaled); };
	public void setShadowOffset(int shadowOffset) { this.shadowOffset = shadowOffset; };

	public String getFont() { return font; };
	public void setFont(String font) { this.font = font; };

	public String getFontStyle() { return fontStyle; };
	public void setFontStyle(String fontStyle) { this.fontStyle = fontStyle; };

	public int getFontSize() { return fontSize; };
	public int getFontSize(double scaled) { return (int)(fontSize*scaled); };
	public void setFontSize(int fontSize) { this.fontSize = fontSize; };

	public String getText() { return text; };
	public void setText(String text) { this.text = text; };

	public void setBox(Boolean box) { this.box = box; }
	public void setTimeZone(String timeZone) { this.timeZone = timeZone; }

	public String getUrl() { return url; };
	public void setUrl(String url) { this.url = url; };

	public int getPageDelay() { return pageDelay; };
	public void setPageDelay(int pageDelay) { this.pageDelay = pageDelay; };

	public double getReloadRate() { return reloadRate; };
	public void setReloadRate(double reloadRate) { this.reloadRate = reloadRate; };

	public Color awt_color() { return awt_color(color); }
	public Color awt_fillColor() { return awt_color(fillColor); }
	public Color awt_shadowColor() { return awt_color(shadowColor); };
	public Color awt_gradientColor() { return awt_color(gradientColor); };
	/** The text's font at source size: its family, fontStyle and fontSize (D2, D3); null when text cannot be drawn here. */
	public Font awt_font() { return getFont(font, fontStyle, fontSize); };
	/** The same at an output's scale. */
	public Font awt_font(double scaled) { return getFont(font, fontStyle, (float)(fontSize * scaled)); };

	public ObjectConfig()
	{
		addFieldForKeyMap("instance", "instanceName");
		addFieldForKeyMap("stream", "streamName");
		addFieldForKeyMap("id", "id");
		created_at = DateTime.now();
		updated_at = DateTime.now();
	}

	public ObjectConfig(String vhost, String appName, String instanceName, String streamName, String id)
	{
		super(vhost, appName);
		this.instanceName = instanceName;
		this.streamName = streamName;
		this.id = id;
		addFieldForKeyMap("instance", "instanceName");
		addToStringKeyMap("instance", instanceName);
		addFieldForKeyMap("stream", "streamName");
		addToStringKeyMap("stream", streamName);
		addFieldForKeyMap("id", "id");
		addToStringKeyMap("id", id);
		created_at = DateTime.now();
		updated_at = DateTime.now();
	}

	@Override
	protected void doInit() throws ResourceException
	{
		super.doInit();
		addToStringKeyMapFromRequest("instance");
		addToStringKeyMapFromRequest("stream");
		addToStringKeyMapFromRequest("id");
		setPathMap();
	}

	// built from its arguments; the cache key and the font agree (D2)
	static Font getFont(String name, String style, float size)
	{
		String key = name + ":" + Fonts.style(style) + ":" + size;
		return fontCache.computeIfAbsent(key, k -> Fonts.font(name, style, size));
	}

	private Color getColor(int r, int g, int b)
	{
		return getColor(r,g,b,255);
	}

	private Color getColor(int r, int g, int b, int a)
	{
		String key= r+":"+g+":"+b+":"+a;
		return colorCache.computeIfAbsent(key, k -> new Color(r, g, b, a));
	}

	private BasicStroke getBasicStroke(int size)
	{
		String key= size + ":";
		return basicStrokeCache.computeIfAbsent(key, k -> new BasicStroke(size));
	}

	public String getInstanceName()
	{
		return this.instanceName;
	}
	public void setInstanceName(String instanceName)
	{
		this.instanceName = instanceName;
	}

	public String getStreamName() { return this.streamName; }
	public void setStreamName(String streamName) { this.streamName = streamName; }

	public String getId()  { return id; }
	public void setId(String id) { this.id = id; }

	@Override
	protected boolean exists(String restVerb)
	{
		if(restVerb.equals("post"))
		{
			return false;
		}
		OverlayStudioModule overlayModule = (OverlayStudioModule)OverlayConfig.getModule(vhostName, appName, instanceName, streamName, new WMSResponse());

		if(overlayModule == null)
		{
			return false;
		}
		oImage = overlayModule.getOverlayImage(streamName);
		if(oImage != null)
		{
			return oImage.getOverlayConfig().getObject(this.id) != null;
		}

		return false;
	}

	//this is get GET
	@Override
	public boolean loadObject()
	{
		boolean retVal = false;
		OverlayStudioModule overlayModule = (OverlayStudioModule)OverlayConfig.getModule(vhostName, appName, instanceName, streamName, new WMSResponse());

		if(overlayModule == null)
		{
			return false;
		}
		oImage = overlayModule.getOverlayImage(streamName);
		if(oImage != null)
		{
			ObjectConfig oldObj = oImage.getOverlayConfig().getObject(this.id);
			if(oldObj ==null)
			{
				log.info("Cloud not find object: " + this.getId());
				return false;
			}
			else
			{
				this.copyFrom(oldObj);
				retVal = true;
			}
		}
		return retVal;
	}

	/** The object types draw() knows. */
	static final java.util.Set<String> TYPES = java.util.Set.of("rectangle", "line", "string", "ellipse", "triangle", "image", "webPage");

	/** Refuses a missing or unknown type as a client error naming the field. */
	void checkType() throws ConfigBaseException
	{
		if(type == null || !TYPES.contains(type))
		{
			ConfigBaseException ex = new ConfigBaseException();
			ex.addError("type", type == null ? "An object needs a type: one of " + new java.util.TreeSet<>(TYPES) : "Unknown type '" + type + "': use one of " + new java.util.TreeSet<>(TYPES));
			throw ex;
		}
		checkTiming();
	}

	/** Refuses malformed text fields (textProblem) as a client error naming the field. */
	void checkText() throws ConfigBaseException
	{
		if(!"string".equals(type))
		{
			return;
		}
		String[] problem = textProblem(textFields());
		if(problem != null)
		{
			ConfigBaseException ex = new ConfigBaseException();
			ex.addError(problem[0], problem[1]);
			throw ex;
		}
	}

	/** Refuses malformed timing fields as a client error naming the field. */
	void checkTiming() throws ConfigBaseException
	{
		String[] problem = timingProblem(showFrom, hideAt, blinkSeconds, fadeIn, fadeOut);
		if(problem != null)
		{
			ConfigBaseException ex = new ConfigBaseException();
			ex.addError(problem[0], problem[1]);
			throw ex;
		}
		checkText();
	}

	//this is the POST
	@Override
	public WMSResponse saveNewObject() throws ConfigBaseException
	{
		checkType();
		WMSResponse2 retVal = new WMSResponse2();
		OverlayStudioModule overlayModule = (OverlayStudioModule)OverlayConfig.getModule(vhostName, appName, instanceName, streamName, retVal);
		if(overlayModule == null)
		{
			ConfigBaseException ex =new ConfigBaseException();
			ex.addError("","Overlay Module not found");
			throw ex;
		}
		oImage = overlayModule.getOverlayImage(streamName);
		if(oImage == null)
		{
			ConfigBaseException ex =new ConfigBaseException();
			ex.addError("","Overlay not created yet");
			throw ex;
		}
		applyDefaults();
		applyDefaults(oImage.getOverlayConfig());
		checkWebPageUrl();
		this.created_at = DateTime.now();
		this.updated_at = DateTime.now();

		// added, or in place of the object with this id, in one swap
		ObjectConfig oldObj = oImage.getOverlayConfig().getObject(this.id);
		this.oImage = oImage;
		oImage.getOverlayConfig().putObject(this);
		oImage.getOverlayConfig().saveObjectToFile();
		oImage.getOverlayConfig().resetUuid();
		OverlayStudioLog.change(this, (oldObj != null ? "replaced" : "added") + " " + type + " object " + label().trim() + " on "
				+ OverlayStudioLog.stream(vhostName, appName, instanceName, streamName));

		retVal.setSuccess();
		retVal.theObject = this;
		return retVal;
	}

	//this is the PUT
	@Override
	public WMSResponse saveObject() throws ConfigBaseException
	{
		WMSResponse2 retVal = new WMSResponse2();
		OverlayStudioModule overlayModule = (OverlayStudioModule)OverlayConfig.getModule(vhostName, appName, instanceName, streamName, retVal);
		if(overlayModule == null)
		{
			ConfigBaseException ex =new ConfigBaseException();
			ex.addError("","Overlay Module not found");
			throw ex;
		}
		oImage = overlayModule.getOverlayImage(streamName);
		ObjectConfig oldObj = oImage.getOverlayConfig().getObject(this.id);
		if(oldObj == null)
		{
			ConfigBaseException ex =new ConfigBaseException();
			ex.addError("","Could not find Object: " + this.id);
			throw ex;
		}
		this.mergeOjbect(oldObj);
		checkTiming();
		checkWebPageUrl();
		// A new object in place of the live one, which a draw may be walking (D12). It takes its own reference on the
		// page capture the old one holds, so releasing the old one later only lowers the count and no draw opens a new one.
		this.oImage = oImage;
		this.created_at = oldObj.created_at;
		this.updated_at = DateTime.now();
		this.shareCaptureOf(oldObj);
		oImage.getOverlayConfig().putObject(this);
		oImage.getOverlayConfig().saveObjectToFile();
		OverlayStudioLog.change(this, "updated " + oldObj.type + " object " + oldObj.label().trim() + " on "
				+ OverlayStudioLog.stream(vhostName, appName, instanceName, streamName));

		retVal.setSuccess();
		retVal.theObject = this;
		oImage.getOverlayConfig().resetUuid();

		return retVal;
	}

	//this is the DELETE
	@Override
	public WMSResponse deleteObject()
	{
		WMSResponse2 retVal = new WMSResponse2();
		retVal.success = false;
		OverlayStudioModule overlayModule = (OverlayStudioModule)OverlayConfig.getModule(vhostName, appName, instanceName, streamName, retVal);
		if(overlayModule == null)
		{
			retVal.message = "Overlay Module not found";
			return retVal;
		}

		oImage = overlayModule.getOverlayImage(streamName);
		if(oImage == null)
		{
			retVal.message = "Overlay not found";
			return retVal;
		}
		ObjectConfig oldObj = oImage.getOverlayConfig().getObject(this.id);
		if(oldObj == null)
		{
			retVal.message = "Could not find Object: " + this.id;
			return retVal;
		}
		oImage.getOverlayConfig().deleteObject(this.id);
		oImage.getOverlayConfig().saveObjectToFile();
		OverlayStudioLog.change(this, "deleted object " + this.id + " on " + OverlayStudioLog.stream(vhostName, appName, instanceName, streamName));
		oImage.getOverlayConfig().resetUuid();
		retVal.setSuccess();
		retVal.message = "Deleted";

		return retVal;
	}

	// Identity, on purpose. 1.x compared some fields and not others (D7); WSE's ConfigBase.equals compares every field
	// by reflection, runtime ones such as the page capture and timestamps included. Nothing compares objects by value.
	@Override
	public boolean equals(Object o)
	{
		return o == this;
	}

	@Override
	public int hashCode()
	{
		return System.identityHashCode(this);
	}

	public Color awt_color(String srt)
	{
		try
		{
			//see if rgb(###,###,###) is passed in
			Pattern c = Pattern.compile("rgb *\\( *([0-9]+), *([0-9]+), *([0-9]+) *\\)");
			Matcher m = c.matcher(srt.toLowerCase());
			if (m.matches())
			{
				return getColor(Integer.parseInt(m.group(1)),  // r
						Integer.parseInt(m.group(2)),  // g
						Integer.parseInt(m.group(3))); // b
			}
			//see if rgb(###,###,###,###) is passed in
			c = Pattern.compile("rgb *\\( *([0-9]+), *([0-9]+), *([0-9]+), *([0-9]+) *\\)");
			m = c.matcher(srt.toLowerCase());
			if (m.matches())
			{
				return getColor(Integer.parseInt(m.group(1)),  // r
						Integer.parseInt(m.group(2)),  // g
						Integer.parseInt(m.group(3)),  // b
						Integer.parseInt(m.group(4))); // a
			}
			return (Color)Color.class.getField(srt.toLowerCase()).get(null);
		}
		catch (IllegalAccessException | IllegalArgumentException | NoSuchFieldException | SecurityException e)
		{
			return Color.black;
		}
	}

	@SuppressWarnings("UseSpecificCatch")
	public void draw(Graphics2D g, double scaled, boolean evenCoords )
	{
		draw(g, scaled, evenCoords, 1.0, System.currentTimeMillis(), 0);
	}

	/**
	 * Draws at this scale with the layer's opacity times timing, 0 to 1 (a fade; see timingAlpha). now is the draw's
	 * one clock and streamStart when the stream's transcoder started (0: not known), for text tokens.
	 */
	@SuppressWarnings("UseSpecificCatch")
	public void draw(Graphics2D g, double scaled, boolean evenCoords, double timing, long now, long streamStart)
	{
		// rounded up to even as the scaled getters read them; the stored fields stay as given
		even = evenCoords;
		if(timing <= 0)
		{
			return;
		}
		log.debug("Drawing image: " + this.id + "<" + this.type + ">");

		if(type == null)
		{
			return;
		}

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		AffineTransform saved = g.getTransform();

		// opacity not set (-1) is fully opaque
		g.setComposite(AlphaComposite.SrcOver.derive((float)((this.opacity < 0 ? 100 : Math.min(100, this.opacity)) / 100.0 * Math.min(1.0, timing))));

            switch (type) {
                case "rectangle"-> {
                    g.setStroke(getBasicStroke(this.getStroke(scaled)));
                    if (shadowColor != null)
                    {
                        if(this.gradientColor != null)
                        {
                            g.setPaint( getGradientPaint(awt_shadowColor(), awt_gradientColor(), this.gradientDirection, this.getX(scaled), this.getY(scaled), this.getX(scaled)+this.getW(scaled), this.getY(scaled)+this.getH(scaled)));
                        }
                        else
                        {
                            g.setColor(this.awt_shadowColor());
                        }
                        
                        if(this.getArcWidth(scaled) >=0 && this.getArcHeight(scaled) >= 0)
                        {
                            g.drawRoundRect(this.getX(scaled) + getShadowOffset(scaled), this.getY(scaled) + getShadowOffset(scaled), this.getW(scaled) - (this.getStroke(scaled) * 2),
                                    this.getH(scaled) - (this.getStroke(scaled) * 2), this.getArcWidth(scaled), this.getArcHeight(scaled));
                        }
                        else
                        {
                            g.drawRect(this.getX(scaled) + getShadowOffset(scaled), this.getY(scaled) + getShadowOffset(scaled), this.getW(scaled) - (this.getStroke(scaled) * 2),
                                    this.getH(scaled) - (this.getStroke(scaled) * 2));
                        }
                    }
                    if(this.fillColor != null)
                    {
                        if(this.gradientColor != null)
                        {
                            g.setPaint( getGradientPaint(awt_fillColor(), awt_gradientColor(), this.gradientDirection, this.getX(scaled), this.getY(scaled), this.getX(scaled)+this.getW(scaled), this.getY(scaled)+this.getH(scaled)));
                        }
                        else
                        {
                            g.setColor(this.awt_fillColor());
                        }
                        if(this.getArcWidth(scaled) >=0 && this.getArcHeight(scaled) >= 0)
                        {
                            g.fillRoundRect(this.getX(scaled), this.getY(scaled), this.getW(scaled) - (this.getStroke(scaled) * 2), this.getH(scaled) - (this.getStroke(scaled) * 2), this.getArcWidth(scaled), this.getArcHeight(scaled));
                        }
                        else
                        {
                            g.fillRect(this.getX(scaled), this.getY(scaled), this.getW(scaled) - (this.getStroke(scaled) * 2), this.getH(scaled) - (this.getStroke(scaled) * 2));
                            
                        }
                    }
                    if(this.gradientColor != null)
                    {
                        g.setPaint( getGradientPaint(awt_color(), awt_gradientColor(), this.gradientDirection, this.getX(scaled), this.getY(scaled), this.getX(scaled)+this.getW(scaled), this.getY(scaled)+this.getH(scaled)));
                    }
                    else
                    {
                        g.setColor(this.awt_color());
                    }
                    if(this.getArcWidth(scaled) >=0 && this.getArcHeight(scaled) >= 0)
                    {
                        g.drawRoundRect(this.getX(scaled), this.getY(scaled), this.getW(scaled) - (this.getStroke(scaled)* 2), this.getH(scaled) - (this.getStroke(scaled) * 2), this.getArcWidth(scaled), this.getArcHeight(scaled));
                        
                    }
                    else {
                        g.drawRect(this.getX(scaled), this.getY(scaled), this.getW(scaled) - (this.getStroke(scaled) * 2), this.getH(scaled) - (this.getStroke(scaled) * 2));
                    }
				}
                case "line" -> {
                    g.setStroke(getBasicStroke(this.getStroke(scaled)));
                    if (shadowColor != null)
                    {
                        if(this.gradientColor != null)
                        {
                            g.setPaint( getGradientPaint(awt_shadowColor(), awt_gradientColor(), this.gradientDirection, this.getX(scaled), this.getY(scaled), this.getX2(scaled), this.getY2(scaled)));
                        }
                        else
                        {
                            g.setColor(this.awt_shadowColor());
                        }
                        g.drawLine(this.getX(scaled)+ getShadowOffset(scaled), this.getY(scaled)+ getShadowOffset(scaled), this.getX2(scaled)+ getShadowOffset(scaled), this.getY2(scaled)+ getShadowOffset(scaled));
                    }
                    if(this.gradientColor != null)
                    {
                        g.setPaint( getGradientPaint(awt_color(), awt_gradientColor(), this.gradientDirection, this.getX(scaled), this.getY(scaled), this.getX2(scaled), this.getY2(scaled)));
                    }
                    else
                    {
                        g.setColor(this.awt_color());
                    }
                    g.drawLine(this.getX(scaled), this.getY(scaled), this.getX2(scaled), this.getY2(scaled));
				}
                case "string" -> TextDraw.draw(this, g, scaled, now, streamStart);
                case "ellipse" -> {
                    g.setStroke(getBasicStroke(this.getStroke(scaled)));
                    if (shadowColor != null)
                    {
                        if(this.gradientColor != null)
                        {
                            g.setPaint( getGradientPaint(awt_shadowColor(), awt_gradientColor(), this.gradientDirection, this.getX(scaled), this.getY(scaled), this.getX(scaled)+this.getW(scaled), this.getY(scaled)+this.getH(scaled)));
                        }
                        else
                        {
                            g.setColor(this.awt_shadowColor());
                        }
                        g.fillOval(this.getX(scaled) + getShadowOffset(scaled), this.getY(scaled) + getShadowOffset(scaled), this.getW(scaled), this.getH(scaled));
                    }
                    if(this.fillColor != null)
                    {
                        if(this.gradientColor != null)
                        {
                            g.setPaint( getGradientPaint(awt_fillColor(), awt_gradientColor(), this.gradientDirection, this.getX(scaled), this.getY(scaled), this.getX(scaled)+this.getW(scaled), this.getY(scaled)+this.getH(scaled)));
                        }
                        else
                        {
                            g.setColor(this.awt_fillColor());
                        }
                        g.fillOval(this.getX(scaled),this.getY(scaled),this.getW(scaled),this.getH(scaled));
                    }
                    if(this.gradientColor != null)
                    {
                        g.setPaint( getGradientPaint(awt_color(), awt_gradientColor(), this.gradientDirection, this.getX(scaled), this.getY(scaled), this.getX(scaled)+this.getW(scaled), this.getY(scaled)+this.getH(scaled)));
                    }
                    else
                    {
                        g.setColor(this.awt_color());
                    }
                    g.drawOval(this.getX(scaled),this.getY(scaled),this.getW(scaled),this.getH(scaled));
				}
                case "triangle" -> {
                    int[] xInts = new int[3];
                    xInts[0] = this.getX(scaled);
                    xInts[1] = this.getX2(scaled);
                    xInts[2] = this.getX3(scaled);
                    int[] yInts = new int[3];
                    yInts[0] = this.getY(scaled);
                    yInts[1] = this.getY2(scaled);
                    yInts[2] = this.getY3(scaled);
                    g.setStroke(getBasicStroke(this.getStroke(scaled)));
                    if (shadowColor != null)
                    {
                        if(this.gradientColor != null)
                        {
                            g.setPaint( getGradientPaint(awt_shadowColor(), awt_gradientColor(), this.gradientDirection, this.getX(scaled), this.getY(scaled), this.getX2(scaled), this.getY2(scaled)));
                        }
                        else
                        {
                            g.setColor(this.awt_shadowColor());
                        }
                        g.fillPolygon(xInts, yInts, 3);
                    }
                    if(this.fillColor != null)
                    {
                        if(this.gradientColor != null)
                        {
                            g.setPaint( getGradientPaint(awt_fillColor(), awt_gradientColor(), this.gradientDirection, this.getX(scaled), this.getY(scaled), this.getX2(scaled), this.getY2(scaled)));
                        }
                        else
                        {
                            g.setColor(this.awt_fillColor());
                        }
                        g.fillPolygon(xInts, yInts, 3);
                    }
                    if(this.gradientColor != null)
                    {
                        g.setPaint( getGradientPaint(awt_color(), awt_gradientColor(), this.gradientDirection, this.getX(scaled), this.getY(scaled), this.getX2(scaled), this.getY2(scaled)));
                    }
                    else
                    {
                        g.setColor(this.awt_color());
                    }
                    g.drawPolygon(xInts,yInts,3);
				}
                case "image" -> {
                    BufferedImage image = decodedImage();
                    if(image != null)
                    {
                        int[] size = imageDrawSize(image.getWidth(), image.getHeight(), scaled);
                        drawImageAt(g, image, this.getX(scaled), this.getY(scaled), size[0], size[1]);
                    }
				}
                case "webPage" -> {
                    try
                    {
                        WebPageCapture capture = getWebPageCapture();
                        long c0 = System.nanoTime();
                        BufferedImage image = capture != null ? capture.capture(sharedMaxAgeMs()) : null;
                        CAPTURE_WAIT_NS.set(CAPTURE_WAIT_NS.get() + System.nanoTime() - c0);
                        if(image != null)
                        {
                            // each frame is keyed once, and scaled once per size, for every layer and stream drawing
                            // this page: the cache is the page's, not this layer's
                            String keySig = keySignature();
                            long seq = capture.frameSeq();
                            BufferedImage source = image;
                            BufferedImage keyed = keyedShared(capture, source, seq);
                            // committed for this scale once its draw publishes (commitDrawn)
                            pendingSeq.put(scaled, seq);
                            lastKeyedImage = keyed;
                            int[] size = webPageDrawSize(this.w, this.h, keyed.getWidth(), keyed.getHeight(), scaled);
                            BufferedImage drawn = size[0] <= 0 || size[1] <= 0 || (size[0] == keyed.getWidth() && size[1] == keyed.getHeight()) ? keyed
                                : capture.derived(source, seq, keySig + "@" + size[0] + "x" + size[1], () -> scaledOnce(keyed, size[0], size[1]));
                            drawImageAt(g, drawn, this.getX(scaled), this.getY(scaled), size[0], size[1]);
                            capture.reloadEvery(reloadListener, reloadRate);
                        }
                    }
					// a failed page draw is logged; the rest of the overlay still draws
					catch(Exception e)
                    {
                        log.error(e);
                    }
				}
                default -> {
					log.warn("Unknown type: " + type);
				}
            }
		g.setTransform(saved);

	}

	// base64Image decoded once per value instead of on every draw
	private transient String decodedFrom = null;
	private transient BufferedImage decoded = null;

	/** The image object's picture, or null when it has none or it is not an image ImageIO can read within the size limit. */
	BufferedImage decodedImage()
	{
		String b64 = this.base64Image;
		if(b64 == null)
		{
			return null;
		}
		synchronized(webPageLock)
		{
			if(!b64.equals(decodedFrom))
			{
				decodedFrom = b64;
				decoded = null;
				try
				{
					byte[] bytes = Base64.decode(b64);
					// the size from the header first, so a small upload cannot declare a frame too large to decode
					int[] size = com.wowza.wms.plugin.overlaystudio.templates.ImageLibrary.dimensions(bytes);
					if(size != null && size[0] <= com.wowza.wms.plugin.overlaystudio.templates.ImageLibrary.MAX_SIDE
							&& size[1] <= com.wowza.wms.plugin.overlaystudio.templates.ImageLibrary.MAX_SIDE)
					{
						decoded = ImageIO.read(new ByteArrayInputStream(bytes));
					}
					else
					{
						log.warn("OverlayStudio: image object " + id + " is not a readable image of at most "
								+ com.wowza.wms.plugin.overlaystudio.templates.ImageLibrary.MAX_SIDE + " pixels a side; not drawn");
					}
				}
				catch(IOException | RuntimeException e)
				{
					log.warn("OverlayStudio: image object " + id + " could not be read: " + e);
				}
			}
			return decoded;
		}
	}

	// The capture for the current url and window size, or null once disposed.
	// what keying and cropping depend on: frames keyed alike are shared between layers and streams
	private String keySignature()
	{
		return awt_keyColor() + "/" + num(keyTolerance) + "/" + num(cropX) + "," + num(cropY) + "," + num(cropW) + "," + num(cropH);
	}

	/** frame (number seq) of capture, keyed and cropped as this layer shows it, made once for every layer and stream that keys alike. */
	public BufferedImage keyedShared(WebPageCapture capture, BufferedImage frame, long seq)
	{
		return capture.derived(frame, seq, keySignature(), () -> {
			long k0 = System.nanoTime();
			BufferedImage k = cropped(keyOut(frame, awt_keyColor(), num(keyTolerance)));
			lastKeyMs = (System.nanoTime() - k0) / 1_000_000;
			return k;
		});
	}

	/**
	 * Whether drawing again would show something new: a web page with a frame this layer has not drawn yet at its
	 * image's active scales (scale 1.0 when it is on no image). Every other kind changes only when the overlay is edited,
	 * which redraws by itself, or at its nextChangeAt.
	 */
	public boolean hasNewFrame()
	{
		OverlayImage img = oImage;
		return hasNewFrame(img != null ? img.activeScales() : java.util.Set.of(1.0));
	}

	/** Whether any of these scales has not drawn this page's latest frame. */
	public boolean hasNewFrame(java.util.Collection<Double> scales)
	{
		if(!"webPage".equals(type))
		{
			return false;
		}
		WebPageCapture c;
		synchronized(webPageLock)
		{
			c = webPageCapture;
		}
		// no capture yet: the next draw opens it
		if(c == null)
		{
			return true;
		}
		long seq = c.frameSeq();
		for(Double s: scales)
		{
			Long drawn = drawnSeq.get(s);
			if(drawn == null || drawn != seq)
			{
				return true;
			}
		}
		return false;
	}

	/** Text may be wider on the next draw (a clock's digits), so it counts as this wide; the canvas clips it. */
	static final int ANY_WIDTH = 1 << 20;

	/**
	 * The part of the canvas this layer can paint at this scale, in canvas pixels, padded for stroke, shadow and
	 * antialiasing; null when it may paint anywhere (a rotated layer, a page or image whose size is not set). Text
	 * spans the whole width over its line height, so a value that grows between draws is never clipped. The overlay
	 * draws only the union of its layers' areas.
	 */
	public java.awt.Rectangle drawBounds(double scaled)
	{
		if(type == null)
		{
			return null;
		}
		java.awt.Rectangle r;
		switch(type)
		{
			case "rectangle", "ellipse" -> r = new java.awt.Rectangle(getX(scaled), getY(scaled), Math.max(0, getW(scaled)), Math.max(0, getH(scaled)));
			case "line" -> r = span(new int[] { getX(scaled), getX2(scaled) }, new int[] { getY(scaled), getY2(scaled) });
			case "triangle" -> r = span(new int[] { getX(scaled), getX2(scaled), getX3(scaled) }, new int[] { getY(scaled), getY2(scaled), getY3(scaled) });
			case "string" -> {
				r = TextDraw.bounds(this, scaled);
				if(r == null)
				{
					return new java.awt.Rectangle();
				}
			}
			case "image" -> {
				BufferedImage image = decodedImage();
				if(image == null)
				{
					return new java.awt.Rectangle();
				}
				int[] size = imageDrawSize(image.getWidth(), image.getHeight(), scaled);
				r = new java.awt.Rectangle(getX(scaled), getY(scaled), size[0], size[1]);
			}
			case "webPage" -> {
				if(w <= 0 || h <= 0)
				{
					return null;
				}
				int[] size = webPageDrawSize(w, h, w, h, scaled);
				r = new java.awt.Rectangle(getX(scaled), getY(scaled), size[0], size[1]);
			}
			default -> {
				return null;
			}
		}
		int pad = getStroke(scaled) + 2 + (shadowColor != null ? Math.abs(getShadowOffset(scaled)) : 0);
		r.grow(pad, pad);
		return r;
	}

	private static java.awt.Rectangle span(int[] xs, int[] ys)
	{
		int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
		for(int i = 0; i < xs.length; i++)
		{
			x0 = Math.min(x0, xs[i]);
			x1 = Math.max(x1, xs[i]);
			y0 = Math.min(y0, ys[i]);
			y1 = Math.max(y1, ys[i]);
		}
		return new java.awt.Rectangle(x0, y0, x1 - x0, y1 - y0);
	}

	/** The frame this layer's draw at this scale used now counts as drawn: that draw was published. */
	public void commitDrawn(double scaled)
	{
		Long s = pendingSeq.remove(scaled);
		if(s != null)
		{
			drawnSeq.put(scaled, s);
		}
	}

	/**
	 * When this layer next looks different without an edit, in epoch milliseconds after now, or Long.MAX_VALUE: date
	 * text at the next boundary of the smallest unit its pattern shows; showFrom, hideAt and the start of a fade out;
	 * every 100 ms while fading; a blink's next toggle while it shows. A web page changes when its page does, which the
	 * frame check finds. streamStart is when the stream's transcoder started, 0 when not known.
	 */
	public long nextChangeAt(long now, long streamStart)
	{
		long next = Long.MAX_VALUE;
		long show = timeOf(showFrom, streamStart, Long.MIN_VALUE), hide = timeOf(hideAt, streamStart, Long.MAX_VALUE);
		if(now >= hide)
		{
			return next;
		}
		if(now < show)
		{
			return show;
		}
		if("string".equals(type) && text != null)
		{
			next = Math.min(next, TextTokens.nextChange(text, tokenContext(now, streamStart)));
		}
		if(hide != Long.MAX_VALUE)
		{
			next = Math.min(next, hide);
		}
		long in = fadeMs(fadeIn), out = fadeMs(fadeOut);
		boolean fading = (in > 0 && show != Long.MIN_VALUE && now < show + in) || (out > 0 && hide != Long.MAX_VALUE && now >= hide - out);
		if(fading)
		{
			next = Math.min(next, now + 100);
		}
		else if(out > 0 && hide != Long.MAX_VALUE)
		{
			next = Math.min(next, hide - out);
		}
		if(blinkSeconds != null && blinkSeconds > 0)
		{
			long period = Math.round(blinkSeconds * 1000);
			next = Math.min(next, (Math.floorDiv(now, period) + 1) * period);
		}
		return next;
	}

	public long nextChangeAt(long now)
	{
		return nextChangeAt(now, 0);
	}

	/**
	 * How much of the layer shows at now, 0 to 1: 0 outside showFrom to hideAt and in a blink's off half, a ramp over
	 * fadeIn after showFrom and fadeOut before hideAt, else 1. streamStart as in nextChangeAt.
	 */
	public double timingAlpha(long now, long streamStart)
	{
		long show = timeOf(showFrom, streamStart, Long.MIN_VALUE), hide = timeOf(hideAt, streamStart, Long.MAX_VALUE);
		if(now < show || now >= hide)
		{
			return 0;
		}
		if(blinkSeconds != null && blinkSeconds > 0 && Math.floorMod(Math.floorDiv(now, Math.round(blinkSeconds * 1000)), 2) == 1)
		{
			return 0;
		}
		double a = 1;
		long in = fadeMs(fadeIn), out = fadeMs(fadeOut);
		if(in > 0 && show != Long.MIN_VALUE)
		{
			a = Math.min(a, (now - show) / (double)in);
		}
		if(out > 0 && hide != Long.MAX_VALUE)
		{
			a = Math.min(a, (hide - now) / (double)out);
		}
		return Math.max(0, Math.min(1, a));
	}

	private static long fadeMs(Double seconds)
	{
		return seconds == null || seconds <= 0 ? 0 : Math.round(seconds * 1000);
	}

	static final java.util.regex.Pattern STREAM_TIME = java.util.regex.Pattern.compile("stream\\+(\\d{1,9}(\\.\\d{1,3})?)");

	/**
	 * showFrom or hideAt in epoch milliseconds: an ISO date-time with its offset, or stream+N from streamStart.
	 * absent when the field is not set; a stream time whose start is not known yet counts as not reached.
	 */
	static long timeOf(String spec, long streamStart, long absent)
	{
		if(spec == null || spec.isBlank())
		{
			return absent;
		}
		java.util.regex.Matcher m = STREAM_TIME.matcher(spec.trim());
		if(m.matches())
		{
			return streamStart > 0 ? streamStart + Math.round(Double.parseDouble(m.group(1)) * 1000) : Long.MAX_VALUE;
		}
		try
		{
			return java.time.OffsetDateTime.parse(spec.trim()).toInstant().toEpochMilli();
		}
		catch(java.time.format.DateTimeParseException e)
		{
			return Long.MAX_VALUE;
		}
	}

	/** Why the timing fields are refused, as {field, message}, or null when they are fine (3.10). */
	public static String[] timingProblem(Object showFrom, Object hideAt, Object blinkSeconds, Object fadeIn, Object fadeOut)
	{
		for(Object[] f: new Object[][] { { "showFrom", showFrom }, { "hideAt", hideAt } })
		{
			if(f[1] == null)
			{
				continue;
			}
			String v = f[1].toString().trim();
			if(!STREAM_TIME.matcher(v).matches())
			{
				try
				{
					java.time.OffsetDateTime.parse(v);
				}
				catch(java.time.format.DateTimeParseException e)
				{
					return new String[] { (String)f[0], f[0] + " must be a date and time with its offset (2027-01-01T18:00:00-05:00) or stream+N seconds" };
				}
			}
		}
		if(blinkSeconds != null && !(blinkSeconds instanceof Number n && n.doubleValue() >= 0.1 && n.doubleValue() <= 3600))
		{
			return new String[] { "blinkSeconds", "blinkSeconds must be a number from 0.1 to 3600" };
		}
		for(Object[] f: new Object[][] { { "fadeIn", fadeIn }, { "fadeOut", fadeOut } })
		{
			if(f[1] != null && !(f[1] instanceof Number n && n.doubleValue() >= 0 && n.doubleValue() <= 2))
			{
				return new String[] { (String)f[0], f[0] + " must be a number of seconds from 0 to 2" };
			}
		}
		return null;
	}

	/** Fields 1.x had and 2.0 refuses, with what replaces each. */
	public static final Map<String, String> REMOVED = Map.of("displayStart", "showFrom", "displayStop", "hideAt", "flashRate", "blinkSeconds", "rotate", "rotation",
			"useDate", "a {time:...} token in the text");

	/** What this layer's tokens draw from at now: its zone, locale, target and end text, and the stream's start. */
	TextTokens.Context tokenContext(long now, long streamStart)
	{
		java.time.ZoneId zone;
		try
		{
			zone = timeZone == null || timeZone.isBlank() ? java.time.ZoneId.systemDefault() : java.time.ZoneId.of(timeZone);
		}
		catch(java.time.DateTimeException e)
		{
			zone = java.time.ZoneId.systemDefault();
		}
		Long t = null;
		if(target != null && !target.isBlank())
		{
			try
			{
				t = java.time.OffsetDateTime.parse(target.trim()).toInstant().toEpochMilli();
			}
			catch(java.time.format.DateTimeParseException e)
			{
				t = null;
			}
		}
		return new TextTokens.Context(now, zone, textLocale(locale), t, endText, streamStart);
	}

	static java.util.Locale textLocale(String tag)
	{
		return tag == null || tag.isBlank() ? java.util.Locale.US : java.util.Locale.forLanguageTag(tag.trim());
	}

	static final java.util.List<String> ALIGNS = java.util.List.of("left", "center", "right");
	/** Text a layer may hold, in characters. */
	public static final int TEXT_MAX = 2000;

	/**
	 * Why a text layer's fields are refused, as {field, message}, or null (plan 3.2, 3.3, 3.10): text up to 2,000
	 * characters whose tokens and braces parse, timeZone an IANA zone, locale a language tag, target a date-time with
	 * its offset, and the layout fields in range.
	 */
	public static String[] textProblem(Map<String, Object> m)
	{
		Object locale = m.get("locale");
		if(locale != null && (!(locale instanceof String l) || java.util.Locale.forLanguageTag(l.trim()).getLanguage().isEmpty()))
		{
			return new String[] { "locale", "locale must be a language tag such as en-US" };
		}
		Object text = m.get("text");
		if(text != null)
		{
			if(!(text instanceof String t))
			{
				return new String[] { "text", "text must be a string" };
			}
			if(t.length() > TEXT_MAX)
			{
				return new String[] { "text", "text is " + t.length() + " characters; the most is " + TEXT_MAX };
			}
			try
			{
				TextTokens.parse(t, textLocale((String)locale));
			}
			catch(IllegalArgumentException e)
			{
				return new String[] { "text", e.getMessage() };
			}
		}
		Object zone = m.get("timeZone");
		if(zone != null)
		{
			try
			{
				java.time.ZoneId.of(zone.toString());
			}
			catch(java.time.DateTimeException e)
			{
				return new String[] { "timeZone", "timeZone must be an IANA zone such as America/New_York" };
			}
		}
		Object target = m.get("target");
		if(target != null)
		{
			try
			{
				java.time.OffsetDateTime.parse(target.toString().trim());
			}
			catch(java.time.format.DateTimeParseException e)
			{
				return new String[] { "target", "target must be a date and time with its offset, such as 2027-01-01T18:00:00-05:00" };
			}
		}
		if(m.get("endText") != null && (!(m.get("endText") instanceof String e) || e.length() > TEXT_MAX))
		{
			return new String[] { "endText", "endText must be a string of at most " + TEXT_MAX + " characters" };
		}
		Object size = m.get("fontSize");
		if(size != null && !(size instanceof Number n && n.doubleValue() >= 1 && n.doubleValue() <= 1000))
		{
			return new String[] { "fontSize", "fontSize must be from 1 to 1000" };
		}
		Object style = m.get("fontStyle");
		if(style != null && !Fonts.STYLES.contains(style.toString()))
		{
			return new String[] { "fontStyle", "fontStyle must be one of " + Fonts.STYLES };
		}
		Object align = m.get("align");
		if(align != null && !ALIGNS.contains(align.toString()))
		{
			return new String[] { "align", "align must be one of " + ALIGNS };
		}
		Object[][] ranges = { { "lineHeight", 0.5, 5.0 }, { "padding", 0.0, 500.0 }, { "radius", 0.0, 500.0 }, { "outlineWidth", 0.0, 20.0 } };
		for(Object[] r: ranges)
		{
			Object v = m.get(r[0]);
			if(v != null && !(v instanceof Number n && n.doubleValue() >= (double)r[1] && n.doubleValue() <= (double)r[2]))
			{
				return new String[] { (String)r[0], r[0] + " must be a number from " + fmt((double)r[1]) + " to " + fmt((double)r[2]) };
			}
		}
		for(String b: new String[] { "box", "autoFit" })
		{
			if(m.get(b) != null && !(m.get(b) instanceof Boolean))
			{
				return new String[] { b, b + " must be true or false" };
			}
		}
		return null;
	}

	private static String fmt(double d)
	{
		return d == Math.rint(d) ? Long.toString((long)d) : Double.toString(d);
	}

	/** This layer's text fields, for textProblem. */
	private Map<String, Object> textFields()
	{
		Map<String, Object> m = new java.util.LinkedHashMap<>();
		m.put("text", text);
		m.put("locale", locale);
		m.put("timeZone", timeZone);
		m.put("target", target);
		m.put("endText", endText);
		m.put("fontSize", fontSize == -1 ? null : fontSize);
		m.put("fontStyle", fontStyle);
		m.put("align", align);
		m.put("lineHeight", lineHeight);
		m.put("padding", padding);
		m.put("radius", radius);
		m.put("outlineWidth", outlineWidth);
		m.put("box", box);
		m.put("autoFit", autoFit);
		m.values().removeIf(java.util.Objects::isNull);
		return m;
	}

	private WebPageCapture getWebPageCapture()
	{
		synchronized(webPageLock)
		{
			if(disposed)
			{
				return null;
			}
			WebPageCapture.Settings settings = webPageSettings();
			if(webPageCapture != null && !webPageCapture.isFor(settings))
			{
				releaseCapture();
			}
			if(webPageCapture == null)
			{
				// shared with every layer, on any stream, that shows the same page at the same size and key
				webPageCapture = WebPageCapture.POOL.acquire(settings);
				webPageCapture.usePageDelay(this::getPageDelay);
				webPageCapture.addReloadListener(reloadListener);
			}
			return webPageCapture;
		}
	}

	/** Takes its own reference on the page capture old holds, when it would show the same page. */
	void shareCaptureOf(ObjectConfig old)
	{
		WebPageCapture c;
		synchronized(old.webPageLock)
		{
			c = old.webPageCapture;
		}
		if(c == null || !"webPage".equals(type))
		{
			return;
		}
		synchronized(webPageLock)
		{
			WebPageCapture.Settings settings = webPageSettings();
			if(webPageCapture == null && !disposed && c.isFor(settings))
			{
				webPageCapture = WebPageCapture.POOL.acquire(settings);
				webPageCapture.usePageDelay(this::getPageDelay);
				webPageCapture.addReloadListener(reloadListener);
			}
		}
	}

	// Caller must hold webPageLock.
	private void releaseCapture()
	{
		webPageCapture.removeReloadListener(reloadListener);
		webPageCapture.forgetReload(reloadListener);
		WebPageCapture.POOL.release(webPageCapture);
		webPageCapture = null;
	}

	/**
	 * How old a shared page's last screenshot may be and still be used: most of this stream's capture interval, so
	 * streams sharing the page do not each take a screenshot per interval.
	 */
	long sharedMaxAgeMs()
	{
		OverlayImage img = oImage;
		double rate = img != null && img.getOverlayConfig() != null ? img.getOverlayConfig().getRefreshRate() : 0;
		return rate > 0 ? (long)(rate * 800) : 0;
	}

	/** Refuses a webPage object whose url may not be captured (validateUrl). */
	public void checkWebPageUrl() throws ConfigBaseException
	{
		if("webPage".equals(type))
		{
			String err = validateUrl(url, CaptureClient.get().rules());
			if(err != null)
			{
				ConfigBaseException ex = new ConfigBaseException();
				ex.addError("url", err);
				throw ex;
			}
		}
	}

	WebPageCapture.Settings webPageSettings()
	{
		int vw = num(viewportWidth) > 0 ? num(viewportWidth) : w;
		int vh = num(viewportHeight) > 0 ? num(viewportHeight) : h;
		return new WebPageCapture.Settings(url, vw, vh, awt_keyColor(), paintBackground == null || paintBackground, label());
	}

	/** Object id plus the url without its query string, for logs and status; queries can carry secrets. */
	String label()
	{
		String u = url == null ? "" : url;
		int q = u.indexOf('?');
		return (id == null ? "" : id) + " " + (q >= 0 ? u.substring(0, q) : u);
	}

	public Color awt_keyColor()
	{
		return keyColor == null ? getColor(1, 2, 3) : awt_color(keyColor);
	}

	/** @return the last keyed capture, or null before the first */
	public BufferedImage getLastKeyedImage()
	{
		return lastKeyedImage;
	}

	/** @return the last unkeyed capture, or null */
	public BufferedImage getLastRawImage()
	{
		WebPageCapture c;
		synchronized(webPageLock)
		{
			c = webPageCapture;
		}
		return c == null ? null : c.lastRawImage();
	}

	/** Capture health for the status endpoint; "idle" until the first draw opens a browser. */
	public Map<String, Object> getCaptureStatus()
	{
		WebPageCapture c;
		synchronized(webPageLock)
		{
			c = webPageCapture;
		}
		if(c != null)
		{
			Map<String, Object> m = c.status();
			m.put("sharedBy", WebPageCapture.POOL.users(c));
			if(m.get("timing") instanceof Map<?, ?> t)
			{
				@SuppressWarnings("unchecked")
				Map<String, Object> timing = (Map<String, Object>)t;
				timing.put("keyMs", lastKeyMs < 0 ? null : lastKeyMs);
				timing.put("scaleMs", lastScaleMs < 0 ? null : lastScaleMs);
			}
			return m;
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("state", disposed ? "closed" : "idle");
		return m;
	}

	/**
	 * @return why a webPage url is refused, or null if it may be used: http or https only, no user name or
	 * password in it, and a host the network rules allow
	 */
	public static String validateUrl(String url, AddressRules rules)
	{
		if(url == null || url.isBlank())
		{
			return "url is required for a webPage object";
		}
		java.net.URI u;
		try
		{
			u = new java.net.URI(url.trim());
		}
		catch(java.net.URISyntaxException e)
		{
			return "url is not valid: " + e.getMessage();
		}
		String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(java.util.Locale.ROOT);
		if(!scheme.equals("http") && !scheme.equals("https"))
		{
			return "only http and https urls can be captured, not '" + scheme + "'";
		}
		if(u.getRawUserInfo() != null)
		{
			return "url must not contain a user name or password";
		}
		String host = u.getHost();
		if(host == null)
		{
			return "url has no host";
		}
		if(host.startsWith("["))
		{
			host = host.substring(1, host.length() - 1);
		}
		int port = u.getPort() > 0 ? u.getPort() : scheme.equals("https") ? 443 : 80;
		if(!rules.checkPort(port).allowed())
		{
			return "Blocked port: " + port + ". Pages are captured from ports " + rules.allowedPorts() + "; add others with the "
					+ EgressRules.PROP_ALLOW_PORTS + " server property.";
		}
		AddressRules.Decision d = rules.check(host);
		if(!d.allowed())
		{
			return "Blocked host: " + host + " resolves to " + d.address() + " (" + d.rule() + "). Allow it with the "
					+ EgressRules.PROP_ALLOW_HOSTS + " server property.";
		}
		return null;
	}

	/**
	 * Keys out every pixel whose red, green and blue are each within tolerance of key: alpha 0, color kept. Every
	 * other pixel is made fully opaque.
	 */
	/**
	 * The keyed frame scaled to width x height, made once per keyed frame and size so a draw is a plain copy. The
	 * same frame at its own size is returned as it is.
	 */
	BufferedImage scaledOnce(BufferedImage keyed, int width, int height)
	{
		if(width <= 0 || height <= 0 || (width == keyed.getWidth() && height == keyed.getHeight()))
		{
			return keyed;
		}
		BufferedImage s = scaledImage;
		if(scaledFrom == keyed && s != null && s.getWidth() == width && s.getHeight() == height)
		{
			return s;
		}
		long t0 = System.nanoTime();
		s = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = s.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(keyed, 0, 0, width, height, null);
		g.dispose();
		lastScaleMs = (System.nanoTime() - t0) / 1_000_000;
		scaledImage = s;
		scaledFrom = keyed;
		return s;
	}

	public static BufferedImage keyOut(BufferedImage image, Color key, int tolerance)
	{
		int tol = Math.max(0, Math.min(255, tolerance));
		int kr = key.getRed(), kg = key.getGreen(), kb = key.getBlue();
		int height = image.getHeight(), width = image.getWidth();
		BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		int[] px = ((java.awt.image.DataBufferInt)out.getRaster().getDataBuffer()).getData();
		// a decoded PNG is byte BGR or ABGR: read its bytes straight, without getRGB's color conversion
		if((image.getType() == BufferedImage.TYPE_3BYTE_BGR || image.getType() == BufferedImage.TYPE_4BYTE_ABGR)
				&& image.getRaster().getDataBuffer() instanceof java.awt.image.DataBufferByte db && image.getRaster().getParent() == null)
		{
			byte[] b = db.getData();
			int step = image.getType() == BufferedImage.TYPE_4BYTE_ABGR ? 4 : 3, o = step - 3;
			for(int i = 0, j = 0; i < px.length; i++, j += step)
			{
				int bl = b[j + o] & 0xff, gr = b[j + o + 1] & 0xff, rd = b[j + o + 2] & 0xff;
				boolean keyed = Math.abs(rd - kr) <= tol && Math.abs(gr - kg) <= tol && Math.abs(bl - kb) <= tol;
				px[i] = (keyed ? 0 : 0xff000000) | (rd << 16) | (gr << 8) | bl;
			}
			return out;
		}
		// whole rows at once; per-pixel getRGB and setRGB are far slower
		int[] src = image.getRGB(0, 0, width, height, null, 0, width);
		System.arraycopy(src, 0, px, 0, src.length);
		for(int i = 0; i < px.length; i++)
		{
			int rgb = px[i] & 0xffffff;
			int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
			boolean keyed = Math.abs(r - kr) <= tol && Math.abs(g - kg) <= tol && Math.abs(b - kb) <= tol;
			px[i] = (keyed ? 0 : 0xff000000) | rgb;
		}
		return out;
	}

	// Force a redraw so the reloaded page is captured even when refreshRate is not set.
	private void onWebPageReloaded()
	{
		OverlayImage img = oImage;
		if(img != null && img.getOverlayConfig() != null)
		{
			img.getOverlayConfig().resetUuid();
		}
	}

	// ConfigBase.copyFrom copies every declared field, including static and runtime ones.
	@Override
	protected void copyFrom(ConfigBase obj)
	{
		for (Field field : obj.getClass().getDeclaredFields())
		{
			int mod = field.getModifiers();
			if (Modifier.isStatic(mod) || Modifier.isTransient(mod))
			{
				continue;
			}
			field.setAccessible(true);
			try
			{
				field.set(this, field.get(obj));
			}
			catch (Exception e)
			{
				log.error(e);
			}
		}
	}

	// Release the headless browser and reload timer when the object is removed. Final: later draws open nothing.
	public void dispose()
	{
		synchronized(webPageLock)
		{
			disposed = true;
			if(webPageCapture != null)
			{
				releaseCapture();
			}
		}
	}

	/**
	 * Size to draw a webPage capture: the object's w x h scaled to the target frame, so a capture placed on a
	 * scaled encode shrinks with it. Falls back to the capture's own size when w or h is not set.
	 */
	static int[] webPageDrawSize(int w, int h, int imageWidth, int imageHeight, double scaled)
	{
		int baseW = w > 0 ? w : imageWidth;
		int baseH = h > 0 ? h : imageHeight;
		return new int[] { (int)(baseW * scaled), (int)(baseH * scaled) };
	}

	/** Size to draw an image object: its own pixel size, scaled to the target frame. */
	static int[] imageDrawSize(int imageWidth, int imageHeight, double scaled)
	{
		return new int[] { (int)(imageWidth * scaled), (int)(imageHeight * scaled) };
	}

	/**
	 * The part of a capture that is drawn: x, y, w, h in the capture's pixels, clamped to it. No crop, or one that
	 * covers the whole capture, returns the image itself.
	 */
	static BufferedImage crop(BufferedImage image, int x, int y, int w, int h)
	{
		if(image == null || w <= 0 || h <= 0)
		{
			return image;
		}
		int cx = Math.max(0, Math.min(x, image.getWidth() - 1));
		int cy = Math.max(0, Math.min(y, image.getHeight() - 1));
		int cw = Math.min(w, image.getWidth() - cx);
		int ch = Math.min(h, image.getHeight() - cy);
		if(cx == 0 && cy == 0 && cw == image.getWidth() && ch == image.getHeight())
		{
			return image;
		}
		return image.getSubimage(cx, cy, cw, ch);
	}

	/** This object's crop of a capture (cropX, cropY, cropW, cropH). */
	BufferedImage cropped(BufferedImage image)
	{
		return crop(image, num(cropX), num(cropY), num(cropW), num(cropH));
	}

	static void drawImageAt(Graphics2D g, BufferedImage image, int x, int y, int width, int height)
	{
		if(width <= 0 || height <= 0)
		{
			return;
		}
		if(width == image.getWidth() && height == image.getHeight())
		{
			g.drawImage(image, x, y, null);
			return;
		}
		Object hint = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(image, x, y, width, height, null);
		if(hint != null)
		{
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, hint);
		}
	}

	public BufferedImage removeColors(BufferedImage image,Color colorsBlackList)
	{
		return keyOut(image, colorsBlackList, 0);
	}


	public GradientPaint getGradientPaint(Color c1, Color c2, String direction, int x, int y, int x2, int y2)
	{
		return switch (this.gradientDirection) {
				case "up" -> new GradientPaint(x, y2, c1, x, y, c2);
				case "down" -> new GradientPaint(x, y, c1, x, y2, c2);
				case "left" -> new GradientPaint(x2, y, c1, x, y, c2);
				case "right" -> new GradientPaint(x, y, c1, x2, y, c2);
				case "downright" -> new GradientPaint(x, y, c1, x2, y2, c2);
				case "downleft" -> new GradientPaint(x2, y, c1, x, y2, c2);
				case "upleft" -> new GradientPaint(x2, y2, c1, x, y, c2);
				case "upright" -> new GradientPaint(x, y2, c1, x2, y, c2);
				default -> new GradientPaint(x, y, c1, x, y2, c2);
			}; //default = down
	}

	public void mergeOjbect(ObjectConfig obj)
	{
		if(this.oImage == null)
		{
			this.oImage = obj.oImage;
		}
		if(this.type == null)
		{
			this.type = obj.type;
		}
		if(this.x == -1)
		{
			this.x = obj.x;
		}
		if(this.y == -1)
		{
			this.y = obj.y;
		}
		if(this.x2 == -1)
		{
			this.x2 = obj.x2;
		}
		if(this.y2 == -1)
		{
			this.y2 = obj.y2;
		}
		if(this.x3 == -1)
		{
			this.x3 = obj.x3;
		}
		if(this.y3 == -1)
		{
			this.y3 = obj.y3;
		}
		if(this.w == -1)
		{
			this.w = obj.w;
		}
		if(this.h == -1)
		{
			this.h = obj.h;
		}
		if(this.created_at == null)
		{
			this.created_at = obj.created_at;
		}
		if(this.showFrom == null)
		{
			this.showFrom = obj.showFrom;
		}
		if(this.hideAt == null)
		{
			this.hideAt = obj.hideAt;
		}
		if(this.blinkSeconds == null)
		{
			this.blinkSeconds = obj.blinkSeconds;
		}
		if(this.fadeIn == null)
		{
			this.fadeIn = obj.fadeIn;
		}
		if(this.fadeOut == null)
		{
			this.fadeOut = obj.fadeOut;
		}
		if(this.opacity == -1)
		{
			this.opacity = obj.opacity;
		}
		if(this.arcHeight == -1)
		{
			this.arcHeight = obj.arcHeight;
		}
		if(this.arcWidth == -1)
		{
			this.arcWidth = obj.arcWidth;
		}
		if(this.zOrder == -1)
		{
			this.zOrder = obj.zOrder;
		}
		if (this.stroke == -1)
		{
			this.stroke = obj.stroke;
		}
		if (this.color == null)
		{
			this.color = obj.color;
		}
		if (this.fillColor == null)
		{
			this.fillColor = obj.fillColor;
		}
		if (this.gradientDirection == null)
		{
			this.gradientDirection = obj.gradientDirection;
		}
		if (this.gradientColor == null)
		{
			this.gradientColor = obj.gradientColor;
		}
		if(this.base64Image == null)
		{
			this.base64Image = obj.base64Image;
		}
		if (this.shadowOffset == -1)
		{
			this.shadowOffset = obj.shadowOffset;
		}
		if (this.shadowColor == null)
		{
			this.shadowColor = obj.shadowColor;
		}
		if (this.type.equals("string"))
		{
			if (this.font == null)
			{
				this.font = obj.font;
			}
			if (this.fontStyle == null)
			{
				this.fontStyle = obj.fontStyle;
			}
			if (this.fontSize == -1)
			{
				this.fontSize = obj.fontSize;
			}
			if (this.text == null)
			{
				this.text = obj.text;
			}
			if(this.box == null) { this.box = obj.box; }
			if(this.align == null) { this.align = obj.align; }
			if(this.lineHeight == null) { this.lineHeight = obj.lineHeight; }
			if(this.background == null) { this.background = obj.background; }
			if(this.padding == null) { this.padding = obj.padding; }
			if(this.radius == null) { this.radius = obj.radius; }
			if(this.outlineColor == null) { this.outlineColor = obj.outlineColor; }
			if(this.outlineWidth == null) { this.outlineWidth = obj.outlineWidth; }
			if(this.autoFit == null) { this.autoFit = obj.autoFit; }
			if(this.timeZone == null) { this.timeZone = obj.timeZone; }
			if(this.locale == null) { this.locale = obj.locale; }
			if(this.target == null) { this.target = obj.target; }
			if(this.endText == null) { this.endText = obj.endText; }
		}
		if (this.type.equals("webPage"))
		{
			if (this.url == null)
			{
				this.url = obj.url;
			}
			if (this.pageDelay == -1)
			{
				this.pageDelay = obj.pageDelay;
			}
			if (this.reloadRate == -1)
			{
				this.reloadRate = obj.reloadRate;
			}
			if (this.viewportWidth == null)
			{
				this.viewportWidth = obj.viewportWidth;
			}
			if (this.viewportHeight == null)
			{
				this.viewportHeight = obj.viewportHeight;
			}
			if (this.keyColor == null)
			{
				this.keyColor = obj.keyColor;
			}
			if (this.keyTolerance == null)
			{
				this.keyTolerance = obj.keyTolerance;
			}
			if (this.paintBackground == null)
			{
				this.paintBackground = obj.paintBackground;
			}
			if (this.cropX == null)
			{
				this.cropX = obj.cropX;
			}
			if (this.cropY == null)
			{
				this.cropY = obj.cropY;
			}
			if (this.cropW == null)
			{
				this.cropW = obj.cropW;
			}
			if (this.cropH == null)
			{
				this.cropH = obj.cropH;
			}
		}
	}

	// whether this draw uses even coordinates; set at the start of draw(), the same for every draw of one overlay
	private transient volatile boolean even;

	private int ev(int v)
	{
		return even ? v + (v % 2) : v;
	}

	/** A boxed field's value, 0 when it was not given. */
	static int num(Integer v)
	{
		return v == null ? 0 : v;
	}

	public void applyDefaults()
	{
		if(this.x == -1)
		{
			this.x = 0;
		}
		if(this.y == -1)
		{
			this.y = 0;
		}
		if(this.x2 == -1)
		{
			this.x2 = 0;
		}
		if(this.y2 == -1)
		{
			this.y2 = 0;
		}
		if(this.x3 == -1)
		{
			this.x3 = 0;
		}
		if(this.y3 == -1)
		{
			this.y3 = 0;
		}
		if(this.w == -1)
		{
			this.w = 0;
		}
		if(this.h == -1)
		{
			this.h = 0;
		}
		if(this.opacity == -1)
		{
			this.opacity = 100;
		}
		if(this.zOrder == -1)
		{
			this.zOrder = 1;
		}
		if(this.gradientColor != null && gradientDirection == null)
		{
			gradientDirection = "down";
		}
	}

	public void applyDefaults(OverlayConfig overlayConfig)
	{
		if (this.stroke == -1)
		{
			this.stroke = overlayConfig.stroke;
		}
		if (this.color == null)
		{
			this.color = overlayConfig.color;
		}
		if (this.fillColor == null)
		{
			this.fillColor = overlayConfig.fillColor;
		}
		if (this.shadowOffset == -1)
		{
			this.shadowOffset = overlayConfig.shadowOffset;
		}
		if (this.shadowColor == null)
		{
			this.shadowColor = overlayConfig.shadowColor;
		}
		if (this.type.equals("string"))
		{
			if (this.font == null)
			{
				this.font = overlayConfig.font;
			}
			if (this.fontStyle == null)
			{
				this.fontStyle = overlayConfig.fontStyle;
			}
			if (this.fontSize == -1)
			{
				this.fontSize = overlayConfig.fontSize;
			}
		}
	}
}
