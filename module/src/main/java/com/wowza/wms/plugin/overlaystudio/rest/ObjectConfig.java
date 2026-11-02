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
import java.text.SimpleDateFormat;
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
	public long draw_count=0;
	public boolean ok_to_draw=true;

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

	@JsonProperty("displayStart")
	protected String displayStart = null;

	@JsonProperty("displayStop")
	protected  String displayStop = null;

	@JsonProperty("flashRate")
	protected int flashRate = -1;

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

	@JsonProperty("rotate")
	protected int rotate = -1;

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

	@JsonProperty("useDate")
	protected Boolean useDate = null;

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

	public String getDisplayStart() { return displayStart; };
	public void setDisplayStart(String displayStart) { this.displayStart = displayStart; };

	public String getDisplayStop() { return displayStop; };
	public void setDisplayStop(String displayStop) { this.displayStop = displayStop; };

	public int getFlashRate() { return flashRate; };
	public void setFlashRate(int flashRate) { this.flashRate =flashRate;};

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

	public int getRotate() { return rotate; };
	public void setRotate(int rotate) { this.rotate = rotate; };

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

	public Boolean getUseDate() { return useDate; };
	public void setUseDate(Boolean useDate) { this.useDate = useDate; };

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
	public Font awt_font() { return  getFont(font, aws_fontStyle(font), fontSize); };
	public Font awt_font(double scaled) { return getFont(font, aws_fontStyle(font), (int)(fontSize*scaled)); };

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

	private Font getFont(String name, int style, int size)
	{
		String key= name+":"+style+":"+size;
		return fontCache.computeIfAbsent(key, k -> new Font(font, style, fontSize));
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

	public int aws_fontStyle(String font)
	{
		//style constant for the Font. The style argument is an integer bitmask that may be PLAIN, or a bitwise union of BOLD and/or ITALIC (for example, ITALIC or BOLD|ITALIC).
		int retVal = Font.PLAIN;
		if(font.toLowerCase().contains("bold"))
			retVal = retVal + Font.BOLD;
		if(font.toLowerCase().contains("italic"))
			retVal = retVal + Font.ITALIC;
		return retVal;
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
		// rounded up to even as the scaled getters read them; the stored fields stay as given
		even = evenCoords;
		draw_count = draw_count + 1;
		DateTime now = DateTime.now();
		DateTimeFormatter df1 = ISODateTimeFormat.dateTimeNoMillis();
		boolean display_ok = true;
		if(displayStart != null)
		{
			try
			{
				DateTime dt;
				if(displayStart.startsWith("+"))
				{
					dt = updated_at.plusSeconds(Integer.parseInt(displayStart.substring(1)));
				}
				else if (displayStart.startsWith("-"))
				{
					dt = updated_at.minusSeconds(Integer.parseInt(displayStart.substring(1)));
				}
				else
				{
					dt = df1.parseDateTime(displayStart);
				}
				if (dt.compareTo(now) > 0)
				{
					display_ok = false;
				}
			}
			catch(NumberFormatException e)
			{
				log.error("Cant parse date:"+ displayStart + " " + e);
			}
		}
		if(display_ok && displayStop != null)
		{
			try
			{
				DateTime dt;
				if(displayStop.startsWith("+"))
				{
					dt = updated_at.plusSeconds(Integer.parseInt(displayStop.substring(1)));
				}
				else if (displayStop.startsWith("-"))
				{
					dt = updated_at.minusSeconds(Integer.parseInt(displayStop.substring(1)));
				}
				else
				{
					dt = df1.parseDateTime(displayStop);
				}
				if (dt.compareTo(now) < 0)
				{
					display_ok = false;
				}
			}
			catch(NumberFormatException e)
			{
				log.error("Cant parse date:"+ displayStop + " " + e,e);
			}

		}
		if(flashRate > 0 && (draw_count % flashRate) == 0 )
		{
			ok_to_draw = !ok_to_draw;
		}

		if(!display_ok || !ok_to_draw)
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
		if(rotate > 0)
		{
			AffineTransform aTrans = AffineTransform.getRotateInstance(Math.toRadians(rotate), this.getX(scaled), this.getY(scaled));
			g.transform(aTrans);
		}

		g.setComposite(AlphaComposite.SrcOver.derive(  (((float)(this.opacity))/100f) ));

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
                case "string" -> {
                    Font newFont = this.awt_font().deriveFont((float)(this.awt_font().getSize() * scaled));
                    g.setFont(newFont);
                    String theText = text;
                    if(useDate != null && useDate)
                    {
                        try
                        {
                            theText = new SimpleDateFormat(text).format(new Date());
                        }
                        catch(IllegalArgumentException e)
                        {
                            log.warn("Could not get date for text, turning off. " + e);
                            useDate = false;
                        }
                    }
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
                        g.drawString(theText, this.getX(scaled) + getShadowOffset(scaled), this.getY(scaled) + getShadowOffset(scaled));
                    }
                    if(this.gradientColor != null)
                    {
                        g.setPaint( getGradientPaint(awt_color(), awt_gradientColor(), this.gradientDirection, this.getX(scaled), this.getY(scaled), this.getX(scaled)+this.getW(scaled), this.getY(scaled)+this.getH(scaled)));
                    }
                    else
                    {
                        g.setColor(this.awt_color());
                    }
                    g.drawString(theText, this.getX(scaled), this.getY(scaled));
				}
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
	 * text at the next boundary of the smallest unit its pattern shows, a display window at its start or stop. A web
	 * page changes when its page does, which the frame check finds.
	 */
	public long nextChangeAt(long now)
	{
		long next = Long.MAX_VALUE;
		if("string".equals(type) && useDate != null && useDate && text != null)
		{
			next = Math.min(next, nextDateBoundary(text, now));
		}
		for(String spec: new String[] { displayStart, displayStop })
		{
			DateTime at = displayTime(spec);
			if(at != null && at.getMillis() > now)
			{
				next = Math.min(next, at.getMillis());
			}
		}
		return next;
	}

	/** The next boundary of the smallest unit a SimpleDateFormat pattern shows, after now; quoted text is ignored. */
	static long nextDateBoundary(String pattern, long now)
	{
		String letters = pattern.replaceAll("'[^']*'", "");
		java.time.ZonedDateTime t = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault());
		if(letters.indexOf('S') >= 0)
		{
			// tenths at the finest: redraws are capped at 10 a second
			return (now / 100 + 1) * 100;
		}
		java.time.temporal.ChronoUnit unit = letters.indexOf('s') >= 0 ? java.time.temporal.ChronoUnit.SECONDS
				: letters.indexOf('m') >= 0 ? java.time.temporal.ChronoUnit.MINUTES
				: letters.matches(".*[HhKka].*") ? java.time.temporal.ChronoUnit.HOURS : java.time.temporal.ChronoUnit.DAYS;
		return t.truncatedTo(unit).plus(1, unit).toInstant().toEpochMilli();
	}

	/** A displayStart or displayStop: an ISO date-time, or +N / -N seconds from the last update; null when absent or bad. */
	private DateTime displayTime(String spec)
	{
		if(spec == null)
		{
			return null;
		}
		try
		{
			if(spec.startsWith("+") || spec.startsWith("-"))
			{
				if(updated_at == null)
				{
					return null;
				}
				int n = Integer.parseInt(spec.substring(1));
				return spec.startsWith("+") ? updated_at.plusSeconds(n) : updated_at.minusSeconds(n);
			}
			return ISODateTimeFormat.dateTimeNoMillis().parseDateTime(spec);
		}
		catch(IllegalArgumentException e)
		{
			return null;
		}
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
		if(this.displayStart == null)
		{
			this.displayStart = obj.displayStart;
		}
		if(this.displayStop == null)
		{
			this.displayStop = obj.displayStop;
		}
		if(this.flashRate == -1)
		{
			this.flashRate = obj.flashRate;
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
		if(this.rotate == -1)
		{
			this.rotate = obj.rotate;
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
			if(this.useDate == null)
			{
				this.useDate = obj.useDate;
			}
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
