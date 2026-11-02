import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Draws text with bundled font files only: never names a logical or system font, never asks the graphics environment
 * for its font list. Shows whether a module that brings its own fonts can draw text on a server with no system fonts.
 * Usage: java BundledOnly <fontFile> <out.png>
 */
public class BundledOnly
{
	public static void main(String[] args) throws Exception
	{
		Font f = Font.createFont(Font.TRUETYPE_FONT, new File(args[0])).deriveFont(48f);
		System.out.println("created family=" + f.getFamily() + " fontName=" + f.getFontName());
		BufferedImage img = new BufferedImage(900, 160, BufferedImage.TYPE_4BYTE_ABGR);
		Graphics2D g = img.createGraphics();
		g.setColor(new Color(20, 20, 20));
		g.fillRect(0, 0, 900, 160);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setColor(Color.WHITE);
		g.setFont(f);
		g.drawString("Live from Boston 12:34:56", 20, 70);
		g.drawString("Прямой эфир Ζωντανά", 20, 135);
		g.dispose();
		ImageIO.write(img, "png", new File(args[1]));
		int lit = 0;
		for(int y = 0; y < 160; y++)
		{
			for(int x = 0; x < 900; x++)
			{
				if((img.getRGB(x, y) & 0xff) > 128)
				{
					lit++;
				}
			}
		}
		System.out.println("drawn ok, bright pixels=" + lit);
	}
}
