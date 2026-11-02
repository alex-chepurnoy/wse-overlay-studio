import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Spike S4, Java side: draws samples.tsv the way the browser page does (white on black, baseline row * 140 + 100,
 * x 20) with the bundled font files, twice: with the module's current hints (antialiasing on, fractional metrics off)
 * and with fractional metrics on. Prints each sample's advance width for both.
 * Usage: java JavaText <dir with samples.tsv and fonts/> <outPrefix>
 */
public class JavaText
{
	public static void main(String[] args) throws Exception
	{
		File dir = new File(args[0]);
		List<String> rows = Files.readAllLines(new File(dir, "samples.tsv").toPath(), StandardCharsets.UTF_8);
		rows.removeIf(String::isBlank);
		Map<String, Font> fonts = new HashMap<>();
		for(boolean fm : new boolean[] {false, true})
		{
			BufferedImage img = new BufferedImage(1100, rows.size() * 140, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = img.createGraphics();
			g.setColor(Color.BLACK);
			g.fillRect(0, 0, img.getWidth(), img.getHeight());
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
				fm ? RenderingHints.VALUE_FRACTIONALMETRICS_ON : RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
			g.setColor(Color.WHITE);
			StringBuilder widths = new StringBuilder();
			for(int i = 0; i < rows.size(); i++)
			{
				String[] c = rows.get(i).split("\t");
				Font base = fonts.get(c[0]);
				if(base == null)
				{
					base = Font.createFont(Font.TRUETYPE_FONT, new File(dir, "fonts/" + c[0]));
					fonts.put(c[0], base);
				}
				Font f = base.deriveFont(Float.parseFloat(c[4]));
				g.setFont(f);
				g.drawString(c[5], 20, i * 140 + 100);
				FontRenderContext frc = g.getFontRenderContext();
				widths.append(i == 0 ? "" : ",").append(String.format("%.3f", f.getStringBounds(c[5], frc).getWidth()));
			}
			g.dispose();
			String out = args[1] + (fm ? "-fm-on.png" : "-fm-off.png");
			ImageIO.write(img, "png", new File(out));
			System.out.println((fm ? "fm_on" : "fm_off") + "=[" + widths + "]");
		}
	}
}
