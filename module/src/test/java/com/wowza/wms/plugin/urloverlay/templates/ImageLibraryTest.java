/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Images for the transcoder's own overlays: only real images, only into content/urloverlay/, only uploads deleted. */
class ImageLibraryTest
{
	@TempDir
	Path vhost;

	private static String png(int w, int h) throws Exception
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), "png", out);
		return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
	}

	@Test
	void anUploadIsSavedListedAndDeleted() throws Exception
	{
		assertEquals("urloverlay/logo.png", ImageLibrary.save(vhost, "logo.png", png(200, 100)));
		assertTrue(Files.isRegularFile(vhost.resolve("content/urloverlay/logo.png")));
		Files.write(vhost.resolve("content/house.png"), Base64.getDecoder().decode(png(50, 50).split(",")[1]));
		List<Map<String, Object>> list = ImageLibrary.list(vhost);
		assertEquals(2, list.size());
		Map<String, Object> logo = list.stream().filter(m -> m.get("file").equals("urloverlay/logo.png")).findFirst().orElseThrow();
		assertEquals(200, logo.get("width"));
		assertEquals(true, logo.get("uploaded"));
		assertTrue(String.valueOf(logo.get("preview")).startsWith("data:image/png;base64,"));
		assertThrows(TemplateFiles.Refused.class, () -> ImageLibrary.delete(vhost, "house.png"), "not uploaded here");
		ImageLibrary.delete(vhost, "urloverlay/logo.png");
		assertFalse(Files.exists(vhost.resolve("content/urloverlay/logo.png")));
		assertTrue(Files.exists(vhost.resolve("content/house.png")));
	}

	@Test
	void onlyImagesWseCanLoadAreSaved() throws Exception
	{
		String text = Base64.getEncoder().encodeToString("not an image".getBytes());
		assertThrows(TemplateFiles.Refused.class, () -> ImageLibrary.save(vhost, "a.png", text));
		assertThrows(TemplateFiles.Refused.class, () -> ImageLibrary.save(vhost, "../a.png", png(10, 10)));
		assertThrows(TemplateFiles.Refused.class, () -> ImageLibrary.save(vhost, "a.svg", png(10, 10)));
		assertThrows(TemplateFiles.Refused.class, () -> ImageLibrary.save(vhost, "big.png", png(ImageLibrary.MAX_SIDE + 1, 10)));
		assertThrows(TemplateFiles.Refused.class, () -> ImageLibrary.safeFile("urloverlay/../../conf/Server.xml"));
		assertFalse(Files.exists(vhost.resolve("content/urloverlay/a.png")));
	}

	// a small PNG whose header claims width x height, as a decompression bomb would
	static byte[] claimedSize(int width, int height) throws Exception
	{
		byte[] b = Base64.getDecoder().decode(png(8, 8).split(",")[1]);
		java.nio.ByteBuffer.wrap(b, 16, 8).putInt(width).putInt(height);
		java.util.zip.CRC32 crc = new java.util.zip.CRC32();
		crc.update(b, 12, 17);
		java.nio.ByteBuffer.wrap(b, 29, 4).putInt((int)crc.getValue());
		return b;
	}

	@Test
	void anImageWhoseDataDoesNotDecodeIsRefused() throws Exception
	{
		// the signature and IHDR chunk only: the header reads as 8 x 8, the pixel data is missing
		byte[] b = java.util.Arrays.copyOf(Base64.getDecoder().decode(png(8, 8).split(",")[1]), 33);
		assertEquals(8, ImageLibrary.dimensions(b)[0]);
		String b64 = Base64.getEncoder().encodeToString(b);
		assertThrows(TemplateFiles.Refused.class, () -> ImageLibrary.save(vhost, "cut.png", b64));
		assertFalse(Files.exists(vhost.resolve("content/urloverlay/cut.png")));
	}

	@Test
	void anImageTooLargeToDecodeIsRefusedFromItsHeader() throws Exception
	{
		byte[] bomb = claimedSize(60000, 60000);
		assertEquals(60000, ImageLibrary.dimensions(bomb)[0], "the header is read, not the pixels");
		String b64 = Base64.getEncoder().encodeToString(bomb);
		TemplateFiles.Refused e = assertThrows(TemplateFiles.Refused.class, () -> ImageLibrary.save(vhost, "bomb.png", b64));
		assertTrue(e.getMessage().contains("pixels on a side"), e.getMessage());
		assertFalse(Files.exists(vhost.resolve("content/urloverlay/bomb.png")));
		// one already in content/ is listed without being decoded
		Files.createDirectories(vhost.resolve("content"));
		Files.write(vhost.resolve("content/bomb.png"), bomb);
		Map<String, Object> listed = ImageLibrary.list(vhost).get(0);
		assertEquals(60000, listed.get("width"));
		assertEquals(null, listed.get("preview"));
	}
}
