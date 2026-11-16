package com.cricket.platform.match;

import com.cricket.platform.scoring.ScoringAccess;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
public class SponsorLogoController {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_DIMENSION = 2048;
    private final JdbcTemplate jdbc;
    private final ScoringAccess access;

    public SponsorLogoController(JdbcTemplate jdbc, ScoringAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    @PostMapping(value = "/api/matches/{matchId}/sponsors/{sponsorId}/logo",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public LogoUploadView upload(@PathVariable UUID matchId, @PathVariable UUID sponsorId,
                                 @RequestPart("logo") MultipartFile logo, Authentication authentication) {
        access.requireMatchManager(matchId, authentication);
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM broadcast_sponsors WHERE id = ? AND match_id = ?",
                Integer.class, sponsorId, matchId);
        if (exists == null || exists == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sponsor was not found for this match.");
        }
        if (logo == null || logo.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a logo image to upload.");
        }
        if (logo.getSize() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Logo must be 2 MB or smaller.");
        }
        try {
            byte[] bytes = logo.getBytes();
            String contentType = detectType(bytes);
            validateDimensions(bytes, contentType);
            jdbc.update("""
                    INSERT INTO broadcast_sponsor_assets (sponsor_id, content_type, image_data, byte_size, updated_at)
                    VALUES (?, ?, ?, ?, now())
                    ON CONFLICT (sponsor_id) DO UPDATE
                    SET content_type = EXCLUDED.content_type, image_data = EXCLUDED.image_data,
                        byte_size = EXCLUDED.byte_size, updated_at = now()
                    """, sponsorId, contentType, bytes, bytes.length);
            String url = "/api/public/sponsor-assets/" + sponsorId;
            jdbc.update("UPDATE broadcast_sponsors SET logo_url = ?, updated_at = now() WHERE id = ?",
                    url, sponsorId);
            return new LogoUploadView(url, contentType, bytes.length);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the uploaded logo.");
        }
    }

    @GetMapping("/api/public/sponsor-assets/{sponsorId}")
    public ResponseEntity<byte[]> getLogo(@PathVariable UUID sponsorId) {
        return jdbc.query("""
                SELECT content_type, image_data FROM broadcast_sponsor_assets WHERE sponsor_id = ?
                """, (rs) -> {
            if (!rs.next()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sponsor logo was not found.");
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(rs.getString("content_type")))
                    .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic())
                    .header("X-Content-Type-Options", "nosniff")
                    .body(rs.getBytes("image_data"));
        }, sponsorId);
    }

    private static String detectType(byte[] bytes) {
        if (bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 0x50
                && bytes[2] == 0x4e && bytes[3] == 0x47 && bytes[4] == 0x0d
                && bytes[5] == 0x0a && bytes[6] == 0x1a && bytes[7] == 0x0a) {
            return "image/png";
        }
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8
                && (bytes[2] & 0xff) == 0xff) return "image/jpeg";
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F'
                && bytes[3] == 'F' && bytes[8] == 'W' && bytes[9] == 'E'
                && bytes[10] == 'B' && bytes[11] == 'P') return "image/webp";
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only genuine PNG, JPG or WEBP images are supported.");
    }

    private static void validateDimensions(byte[] bytes, String contentType) throws IOException {
        int width;
        int height;
        if ("image/webp".equals(contentType)) {
            int[] dimensions = webpDimensions(bytes);
            width = dimensions[0];
            height = dimensions[1];
        } else {
            try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The uploaded image could not be decoded.");
                }
                ImageReader reader = readers.next();
                try {
                    reader.setInput(input, true, true);
                    width = reader.getWidth(0);
                    height = reader.getHeight(0);
                } finally {
                    reader.dispose();
                }
            }
        }
        if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Logo dimensions must be between 1 and 2048 pixels.");
        }
    }

    private static int[] webpDimensions(byte[] bytes) {
        int offset = 12;
        while (offset + 8 <= bytes.length) {
            String chunk = new String(bytes, offset, 4, java.nio.charset.StandardCharsets.US_ASCII);
            long size = littleEndian32(bytes, offset + 4);
            int data = offset + 8;
            if (size > bytes.length - data) break;
            if ("VP8X".equals(chunk) && size >= 10) {
                return new int[]{1 + littleEndian24(bytes, data + 4), 1 + littleEndian24(bytes, data + 7)};
            }
            if ("VP8 ".equals(chunk) && size >= 10 && (bytes[data + 3] & 0xff) == 0x9d
                    && (bytes[data + 4] & 0xff) == 0x01 && (bytes[data + 5] & 0xff) == 0x2a) {
                return new int[]{littleEndian16(bytes, data + 6) & 0x3fff,
                        littleEndian16(bytes, data + 8) & 0x3fff};
            }
            if ("VP8L".equals(chunk) && size >= 5 && (bytes[data] & 0xff) == 0x2f) {
                int b1 = bytes[data + 1] & 0xff;
                int b2 = bytes[data + 2] & 0xff;
                int b3 = bytes[data + 3] & 0xff;
                int b4 = bytes[data + 4] & 0xff;
                return new int[]{1 + b1 + ((b2 & 0x3f) << 8),
                        1 + ((b2 & 0xc0) >> 6) + (b3 << 2) + ((b4 & 0x0f) << 10)};
            }
            offset = data + (int) size + ((int) size & 1);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The WEBP image dimensions could not be read.");
    }

    private static int littleEndian16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static int littleEndian24(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16);
    }

    private static long littleEndian32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL) | ((bytes[offset + 1] & 0xffL) << 8)
                | ((bytes[offset + 2] & 0xffL) << 16) | ((bytes[offset + 3] & 0xffL) << 24);
    }

    public record LogoUploadView(String logoUrl, String contentType, int sizeBytes) {}
}
