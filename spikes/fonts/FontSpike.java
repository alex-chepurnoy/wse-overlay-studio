import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Spike S4 (fonts) for the Overlay Studio 2.0 plan: can WSE's own Java register bundled TrueType fonts, pick the right
 * face for bold, draw the scripts we care about, and what do Java's logical fonts resolve to on this machine.
 * Usage: java FontSpike <fontDir> <outDir>. Prints a report; writes one PNG per font and style to outDir.
 */
public class FontSpike
{
	static final String[] LOGICAL = {"Serif", "SansSerif", "Monospaced", "Dialog", "DialogInput"};
	static final Map<String, String> SAMPLES = new LinkedHashMap<>();
	static
	{
		SAMPLES.put("latin", "Live from Boston, 3:45 PM");
		SAMPLES.put("accents", "Café Zürich Ñandú Åse");
		SAMPLES.put("cyrillic", "Прямой эфир");
		SAMPLES.put("greek", "Ζωντανά");
		SAMPLES.put("cjk", "生中継");
		SAMPLES.put("emoji", "🔴 LIVE");
	}

	public static void main(String[] args) throws Exception
	{
		File fontDir = new File(args[0]);
		File outDir = new File(args[1]);
		outDir.mkdirs();
		System.out.println("java.version=" + System.getProperty("java.version") + " vendor=" + System.getProperty("java.vendor"));
		System.out.println("os=" + System.getProperty("os.name") + " " + System.getProperty("os.version") + " arch=" + System.getProperty("os.arch"));
		System.out.println("java.awt.headless=" + System.getProperty("java.awt.headless") + " isHeadless=" + GraphicsEnvironment.isHeadless());

		long t0 = System.nanoTime();
		GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
		String[] families = ge.getAvailableFontFamilyNames();
		System.out.printf("families_before=%d (%.0f ms)%n", families.length, (System.nanoTime() - t0) / 1e6);
		System.out.println("families_sample=" + String.join(", ", Arrays.asList(families).subList(0, Math.min(25, families.length))));

		System.out.println();
		System.out.println("== logical fonts");
		for(String name : LOGICAL)
		{
			Font f = new Font(name, Font.PLAIN, 40);
			System.out.printf("%-12s family=%s fontName=%s width('Agw0123')=%d%s%n", name, f.getFamily(), f.getFontName(),
				width(f, "Agw0123"), coverage(f));
		}

		System.out.println();
		System.out.println("== bundled fonts");
		File[] files = fontDir.listFiles((d, n) -> n.toLowerCase().endsWith(".ttf"));
		Arrays.sort(files);
		List<Font> created = new ArrayList<>();
		for(File file : files)
		{
			long t = System.nanoTime();
			Font base;
			try
			{
				base = Font.createFont(Font.TRUETYPE_FONT, file);
			}
			catch(Exception e)
			{
				System.out.println(file.getName() + " createFont FAILED " + e);
				continue;
			}
			double ms = (System.nanoTime() - t) / 1e6;
			boolean reg = ge.registerFont(base);
			System.out.printf("%s createFont=%.1f ms family=%s fontName=%s psName=%s style=%d registerFont=%s%n", file.getName(), ms,
				base.getFamily(), base.getFontName(), base.getPSName(), base.getStyle(), reg);
			created.add(base);
		}
		System.out.println("register_again=" + (created.isEmpty() ? "n/a" : ge.registerFont(created.get(0))) + " (same font a second time)");
		System.out.println("families_after=" + ge.getAvailableFontFamilyNames().length);

		System.out.println();
		System.out.println("== style resolution: new Font(family, style, 40) after registering");
		List<String> fams = new ArrayList<>();
		for(Font f : created)
		{
			if(!fams.contains(f.getFamily()))
			{
				fams.add(f.getFamily());
			}
		}
		for(String fam : fams)
		{
			for(int style : new int[] {Font.PLAIN, Font.BOLD, Font.ITALIC, Font.BOLD | Font.ITALIC})
			{
				Font f = new Font(fam, style, 40);
				System.out.printf("%-20s %-10s -> fontName=%s family=%s width('Agw0123')=%d%n", fam, styleName(style), f.getFontName(),
					f.getFamily(), width(f, "Agw0123"));
			}
			Font plain = new Font(fam, Font.PLAIN, 40);
			System.out.printf("%-20s coverage:%s%n", fam, coverage(plain));
			System.out.printf("%-20s digits: %s; clock widths 00:00:00=%d 11:11:11=%d 12:34:56=%d%n", fam, digitWidths(plain),
				width(plain, "00:00:00"), width(plain, "11:11:11"), width(plain, "12:34:56"));
			for(int style : new int[] {Font.PLAIN, Font.BOLD})
			{
				render(new Font(fam, style, 48), new File(outDir, fam.replaceAll("[^A-Za-z0-9]", "") + "-" + styleName(style) + ".png"));
			}
		}

		System.out.println();
		System.out.println("== derived from the created Font object (no registration needed)");
		for(Font f : created)
		{
			Font d = f.deriveFont(Font.BOLD, 40f);
			System.out.printf("%-34s deriveFont(BOLD) fontName=%s width=%d vs plain=%d%n", f.getFontName(), d.getFontName(),
				width(d, "Agw0123"), width(f.deriveFont(40f), "Agw0123"));
		}

		System.out.println();
		System.out.println("== unknown family");
		Font unknown = new Font("No Such Family 123", Font.PLAIN, 40);
		System.out.println("new Font(\"No Such Family 123\") -> family=" + unknown.getFamily() + " fontName=" + unknown.getFontName());
		Runtime rt = Runtime.getRuntime();
		System.out.printf("heap_used=%.1f MB%n", (rt.totalMemory() - rt.freeMemory()) / 1048576.0);
	}

	static String styleName(int s)
	{
		return s == Font.PLAIN ? "plain" : s == Font.BOLD ? "bold" : s == Font.ITALIC ? "italic" : "bolditalic";
	}

	static int width(Font f, String s)
	{
		BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		FontMetrics fm = g.getFontMetrics(f);
		int w = fm.stringWidth(s);
		g.dispose();
		return w;
	}

	static String coverage(Font f)
	{
		StringBuilder sb = new StringBuilder();
		for(Map.Entry<String, String> e : SAMPLES.entrySet())
		{
			int up = f.canDisplayUpTo(e.getValue());
			sb.append(' ').append(e.getKey()).append('=').append(up == -1 ? "ok" : "stops@" + up);
		}
		return sb.toString();
	}

	static String digitWidths(Font f)
	{
		FontRenderContext frc = new FontRenderContext(null, true, true);
		StringBuilder sb = new StringBuilder();
		double min = Double.MAX_VALUE, max = 0;
		for(char c = '0'; c <= '9'; c++)
		{
			double w = f.getStringBounds(String.valueOf(c), frc).getWidth();
			min = Math.min(min, w);
			max = Math.max(max, w);
		}
		sb.append(String.format("min=%.2f max=%.2f %s", min, max, Math.abs(max - min) < 0.01 ? "tabular" : "proportional"));
		return sb.toString();
	}

	static void render(Font f, File out) throws Exception
	{
		BufferedImage img = new BufferedImage(900, 260, BufferedImage.TYPE_4BYTE_ABGR);
		Graphics2D g = img.createGraphics();
		g.setColor(new Color(20, 20, 20));
		g.fillRect(0, 0, 900, 260);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
		g.setColor(Color.WHITE);
		g.setFont(f);
		g.drawString(SAMPLES.get("latin"), 20, 60);
		g.drawString(SAMPLES.get("accents") + "  12:34:56", 20, 130);
		g.drawString(SAMPLES.get("cyrillic") + " " + SAMPLES.get("greek"), 20, 200);
		g.setFont(new Font("SansSerif", Font.PLAIN, 14));
		g.drawString(f.getFontName(), 20, 245);
		g.dispose();
		ImageIO.write(img, "png", out);
	}
}
