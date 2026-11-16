package com.cricket.platform.match;

import com.cricket.platform.scoring.ScoringAccess;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
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
                    .header(HttpHeaders.X_CONTENT_TYPE_OPTIONS, "nosniff")
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
        if ("image/webp".equals(contentType)) return;
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null || image.getWidth() < 1 || image.getHeight() < 1
                || image.getWidth() > MAX_DIMENSION || image.getHeight() > MAX_DIMENSION) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Logo dimensions must be between 1 and 2048 pixels.");
        }
    }

    public record LogoUploadView(String logoUrl, String contentType, int sizeBytes) {}
}
