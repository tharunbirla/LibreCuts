// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 CapCraft contributors

//! Scrub copies: a small copy of a video whose every frame is a JPEG of
//! its own, read while the playhead is dragged.
//!
//! A camera's file holds a whole picture once a second or so and the
//! frames between as changes from it, so landing on one of those decodes
//! from the last whole picture forward: up to a second of frames at the
//! file's full size for every step of a drag. In a scrub copy any instant
//! is one seek and one small decode away, whatever came before it. The
//! copy is coarse - it is shown only while the picture moves under a
//! finger, and the paused monitor reads the file itself.

use std::path::{Path, PathBuf};

use concat_core::frame::Frame;
use concat_core::time::FrameRate;
use ffmpeg_the_third as ffmpeg;
use ffmpeg_the_third::codec::encoder;
use ffmpeg_the_third::format::Pixel;
use ffmpeg_the_third::software::scaling;
use ffmpeg_the_third::util::frame::video::Video;

use crate::encode::FrameSink;
use crate::error::{Error, Result};
use crate::ffi;

/// Writes a scrub copy: MJPEG in a QuickTime file, so every frame is a key
/// frame and the index finds each one. JPEG's own levels and matrix - the
/// whole 0-255, BT.601 - which is what a reader assumes of it untold.
pub struct ScrubWriter {
    path: PathBuf,
    output: ffmpeg::format::context::Output,
    encoder: encoder::video::Encoder,
    scaler: scaling::Context,
    encoder_time_base: ffmpeg::Rational,
    stream_time_base: ffmpeg::Rational,
    width: u32,
    height: u32,
    written: u64,
    finished: bool,
}

impl ScrubWriter {
    /// Opens `path` for a `width` by `height` copy at `rate`, each frame a
    /// JPEG at `quality`: the quantiser scale, 2 (best) to 31 (worst).
    pub fn create(
        path: impl AsRef<Path>,
        width: u32,
        height: u32,
        rate: FrameRate,
        quality: u8,
    ) -> Result<Self> {
        ffi::init();
        let path = path.as_ref();
        let fps = rate.fps();
        let rate = ffmpeg::Rational::new(fps.numerator() as i32, fps.denominator() as i32);
        let time_base = rate.invert();
        let mut output =
            ffmpeg::format::output(path).map_err(|error| ffi::fail("create", path, error))?;
        let codec = encoder::find_by_name("mjpeg").ok_or_else(|| Error::Missing {
            what: "encoder",
            name: "mjpeg".to_owned(),
        })?;
        let mut video = ffmpeg::codec::Context::new_with_codec(codec)
            .encoder()
            .video()
            .map_err(|error| ffi::fail("scrub encoder", path, error))?;
        video.set_width(width);
        video.set_height(height);
        video.set_format(Pixel::YUVJ420P);
        video.set_time_base(time_base);
        video.set_frame_rate(Some(rate));
        video.set_color_range(ffmpeg::color::Range::JPEG);
        video.set_colorspace(ffmpeg::color::Space::BT470BG);
        let quality = i32::from(quality.clamp(2, 31));
        video.set_qmin(quality);
        video.set_qmax(quality);
        if output
            .format()
            .flags()
            .contains(ffmpeg::format::Flags::GLOBAL_HEADER)
        {
            video.set_flags(ffmpeg::codec::Flags::GLOBAL_HEADER);
        }
        let encoder = video
            .open()
            .map_err(|error| ffi::fail("open scrub encoder", path, error))?;
        {
            let mut stream = output
                .add_stream(codec)
                .map_err(|error| ffi::fail("add stream", path, error))?;
            stream.copy_parameters_from_context(&encoder);
            stream.set_time_base(time_base);
        }
        output
            .write_header()
            .map_err(|error| ffi::fail("write header", path, error))?;
        let stream_time_base = output
            .stream(0)
            .map(|stream| stream.time_base())
            .unwrap_or(time_base);
        // swscale's own coefficients for a J format: BT.601 at full range,
        // the JPEG convention the stream is tagged with.
        let scaler = scaling::Context::get(
            Pixel::RGBA,
            width,
            height,
            Pixel::YUVJ420P,
            width,
            height,
            scaling::Flags::BILINEAR,
        )
        .map_err(|error| ffi::fail("scaler", path, error))?;
        Ok(Self {
            path: path.to_path_buf(),
            output,
            encoder,
            scaler,
            encoder_time_base: time_base,
            stream_time_base,
            width,
            height,
            written: 0,
            finished: false,
        })
    }

    /// How many frames have been written so far.
    pub const fn written(&self) -> u64 {
        self.written
    }

    /// Writes every packet the encoder has ready. MJPEG is one packet a
    /// frame, out as soon as the frame is in.
    fn drain(&mut self) -> Result<()> {
        loop {
            let mut packet = ffmpeg::Packet::empty();
            match self.encoder.receive_packet(&mut packet) {
                Ok(()) => {
                    packet.set_duration(1);
                    packet.set_stream(0);
                    packet.rescale_ts(self.encoder_time_base, self.stream_time_base);
                    packet
                        .write_interleaved(&mut self.output)
                        .map_err(|error| ffi::fail("write packet", &self.path, error))?;
                }
                Err(ffmpeg::Error::Eof) => return Ok(()),
                Err(error) if ffi::is_again(&error) => return Ok(()),
                Err(error) => return Err(ffi::fail("encode", &self.path, error)),
            }
        }
    }
}

impl FrameSink for ScrubWriter {
    fn write_frame(&mut self, frame: &Frame) -> Result<()> {
        if frame.width() != self.width || frame.height() != self.height {
            return Err(Error::FrameSizeMismatch {
                want_width: self.width,
                want_height: self.height,
                got_width: frame.width(),
                got_height: frame.height(),
            });
        }
        if self.finished {
            return Err(Error::Io {
                path: self.path.clone(),
                source: std::io::Error::other("the scrub copy was already finished"),
            });
        }
        let mut rgba = Video::new(Pixel::RGBA, self.width, self.height);
        {
            let stride = rgba.stride(0);
            let row = self.width as usize * 4;
            let data = rgba.data_mut(0);
            for (y, source) in frame.pixels().chunks_exact(row).enumerate() {
                data[y * stride..y * stride + row].copy_from_slice(source);
            }
        }
        let mut converted = Video::empty();
        self.scaler
            .run(&rgba, &mut converted)
            .map_err(|error| ffi::fail("convert", &self.path, error))?;
        converted.set_pts(Some(self.written as i64));
        self.encoder
            .send_frame(&converted)
            .map_err(|error| ffi::fail("encode", &self.path, error))?;
        self.written += 1;
        self.drain()
    }

    fn finish(&mut self) -> Result<()> {
        if self.finished {
            return Ok(());
        }
        self.finished = true;
        self.encoder
            .send_eof()
            .map_err(|error| ffi::fail("encode", &self.path, error))?;
        self.drain()?;
        self.output
            .write_trailer()
            .map_err(|error| ffi::fail("write trailer", &self.path, error))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::decode::{DecodeOptions, Decoder, FrameSource, SeekableSource};
    use concat_core::time::Rational;

    /// A scrub copy holds every frame as a key frame: a seek lands on the
    /// frame asked for, with nothing before it to decode.
    #[test]
    fn every_frame_of_a_scrub_copy_stands_alone() {
        let dir = std::env::temp_dir().join(format!("concat-scrub-{}", std::process::id()));
        std::fs::create_dir_all(&dir).expect("temp dir");
        let path = dir.join("copy.mov");
        {
            let mut writer =
                ScrubWriter::create(&path, 320, 180, FrameRate::THIRTY, 6).expect("opens");
            for index in 0..30u8 {
                let mut frame = Frame::black(320, 180);
                frame.fill([index * 8, 40, 200, 255]);
                writer.write_frame(&frame).expect("writes");
            }
            writer.finish().expect("finishes");
            assert_eq!(writer.written(), 30);
        }
        let info = crate::probe(&path).expect("probes");
        let video = info.video.expect("has pictures");
        assert_eq!(
            (video.codec.as_str(), video.width, video.height),
            ("mjpeg", 320, 180)
        );
        let mut decoder =
            Decoder::open(&path, &DecodeOptions::default().in_software()).expect("opens");
        decoder
            .seek(FrameRate::THIRTY.time_of_frame(20))
            .expect("seeks");
        let frame = decoder.next_frame().expect("decodes").expect("a frame");
        let at = decoder.position().expect("a timestamp");
        assert_eq!(
            FrameRate::THIRTY.frame_at(at + Rational::new(1, 60)),
            20,
            "the seek landed on the frame itself"
        );
        // Red, as written for frame 20, give or take JPEG.
        let red = frame.pixels()[0];
        assert!(red.abs_diff(160) < 12, "frame 20's red came back {red}");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
