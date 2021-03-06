(ns longshi.fressian.js
  (:import [goog.math Long]
           [goog.string StringBuffer])
  (:require [longshi.fressian.byte-stream-protocols :as bsp]
            [longshi.fressian.protocols :as p]
            [longshi.fressian.utils :refer [make-byte-array make-data-view]]
            [longshi.fressian.byte-stream :as bs]))

(def codes
  #js {
       :NULL 0xF7
       :TRUE 0xF5
       :FALSE 0xF6
       :FLOAT 0xF9
       :DOUBLE 0xFA
       :DOUBLE_0 0xFB
       :DOUBLE_1 0xFC
       :INT_PACKED_2_ZERO 0x50
       :INT_PACKED_3_ZERO 0x68
       :INT_PACKED_4_ZERO 0x72
       :INT_PACKED_5_ZERO 0x76
       :INT_PACKED_6_ZERO 0x7A
       :INT_PACKED_7_ZERO 0x7E
       :INT 0xF8
       :STRING 0xE3
       :STRING_PACKED_LENGTH_START 0xDA
       :STRING_CHUNK 0xE2
       :BYTES_CHUNK 0xD8
       :BYTES 0xD9
       :BYTES_PACKED_LENGTH_START 0xD0
       })

(def ranges
  #js {
       :STRING_PACKED_LENGTH_END 8
       :BYTES_PACKED_LENGTH_END 8
       :BYTE_CHUNK_SIZE 65535
       })
;;Integer constants
(def ^:private max-neg-js-hb-int (bit-shift-left -1 21))
(def ^:private max-pos-js-hb-int (dec (bit-shift-left 1 21)))
(def ^:private two-power-16 (bit-shift-left 1 16))
(def ^:private two-power-24 (bit-shift-left 1 24))
(def ^:private two-power-32 (* two-power-16 two-power-16))
;;Integer helpers
(defn bit-switch [x]
  (cond
    (or (.equals Long.ONE x) (.equals Long.NEG_ONE x)) 64
    :else
    (if (neg? x)
      (- 64 (.getNumBitsAbs (.not x)))
      (- 64 (.getNumBitsAbs x)))))

(defn is-int [x]
  (zero? (js-mod x 1)))

(defn string-chunk-utf8! [s start buffer]
  (loop [str-pos start buf-pos 0]
    (let [ch (.charCodeAt s str-pos)
          ch-enc-size (cond
                        (<= ch 0x007f) 1
                        (> ch 0x07ff) 3
                          :else 2)]
      (if (and (< str-pos (alength s)) (<= (+ buf-pos ch-enc-size) (alength buffer)))
        (do
          (case ch-enc-size
            1 (aset buffer buf-pos ch)
            2 (do
                (aset buffer buf-pos (bit-or (bit-and (bit-shift-right ch 6) 0x1f) 0xc0))
                (aset buffer (inc buf-pos) (bit-or (bit-and (bit-shift-right ch 0) 0x3f) 0x80)))
            3 (do
                (aset buffer buf-pos (bit-or (bit-and (bit-shift-right ch 12) 0x0f) 0xe0))
                (aset buffer (inc buf-pos) (bit-or (bit-and (bit-shift-right ch 6) 0x3f) 0x80))
                (aset buffer (+ 2 buf-pos) (bit-or (bit-and (bit-shift-right ch 0) 0x3f) 0x80))))
          (recur (inc str-pos) (+ buf-pos ch-enc-size)))
        #js [str-pos buf-pos]))))

(extend-type bs/ByteOutputStream
  p/FressianWriter
  (write-null! [bos] (bsp/write! bos (.-NULL codes)))
  (write-boolean! [bos b] (bsp/write! bos (if b (.-TRUE codes) (.-FALSE codes))))
  (write-string! [bos s]
    (let [max-bytes (Math/min (* (alength s) 3) 65536)
          sba (make-byte-array max-bytes)]
      (loop [str-pos 0]
        (let [sa (string-chunk-utf8! s str-pos sba)
              new-str-pos (aget sa 0)
              buf-pos (aget sa 1)]
          (cond
            (< buf-pos (.-STRING_PACKED_LENGTH_END ranges)) (bsp/write! bos (+ (.-STRING_PACKED_LENGTH_START codes) buf-pos))
            (= new-str-pos (alength s))
              (do
                (bsp/write! bos (.-STRING codes))
                (p/write-int! bos buf-pos))
            :else
              (do
                (bsp/write! bos (.-STRING_CHUNK codes))
                (p/write-int! bos buf-pos)))
          (bsp/write-bytes! bos sba 0 buf-pos)
          (if (< new-str-pos (alength s))
            (recur new-str-pos))))))
  (write-bytes! [bos b]
    (if (< (alength b) (.-BYTES_PACKED_LENGTH_END ranges))
      (do
        (bsp/write! bos (+ (alength b) (.-BYTES_PACKED_LENGTH_START codes)))
        (bsp/write-bytes! bos b 0 (alength b)))
      (loop [offset 0 length (alength b)]
        (if (> length (.-BYTE_CHUNK_SIZE ranges))
          (do
            (bsp/write! bos (.-BYTES_CHUNK codes))
            (p/write-int! bos (.-BYTE_CHUNK_SIZE ranges))
            (bsp/write-bytes! bos b offset (.-BYTE_CHUNK_SIZE ranges))
            (recur (+ offset (.-BYTE_CHUNK_SIZE ranges)) (- length (.-BYTE_CHUNK_SIZE ranges))))
          (do
            (bsp/write! bos (.-BYTES codes))
            (p/write-int! bos length)
            (bsp/write-bytes! bos b offset length))))))
  (write-int! [bos i]
    (p/write-long! bos (Long.fromNumber i)))
  (write-long! [bos l]
    (let [lb (.getLowBits l)
          hb (.getHighBits l)
          bits (bit-switch l)]
      (case bits
        (1 2 3 4 5 6 7 8 9 10 11 12 13 14)
        (do
          (bsp/write! bos (.-INT codes))
          (bsp/write-int32! bos hb)
          (bsp/write-int32! bos lb))
        (15 16 17 18 19 20 21 22)
        (let [ic (bit-shift-right hb 16)]
          (do
            (bsp/write! bos (+ (.-INT_PACKED_7_ZERO codes) ic))
            (bsp/write-int16! bos hb)
            (bsp/write-int32! bos lb)))
        (23 24 25 26 27 28 29 30)
        (let [ic (bit-shift-right hb 8)]
          (do
            (bsp/write! bos (+ (.-INT_PACKED_6_ZERO codes) ic))
            (bsp/write! bos hb)
            (bsp/write-int32! bos lb)))
        (31 32 33 34 35 36 37 38)
        (do
          (bsp/write! bos (+ (.-INT_PACKED_5_ZERO codes) hb))
          (bsp/write-int32! bos lb))
        (39 40 41 42 43 44)
        (do
          (bsp/write! bos (+ (.-INT_PACKED_4_ZERO codes) (bit-shift-right lb 24)))
          (bsp/write-int24! bos lb))
        (45 46 47 48 49 50 51)
        (do
          (bsp/write! bos (+ (.-INT_PACKED_3_ZERO codes) (bit-shift-right lb 16)))
          (bsp/write-int16! bos lb))
        (52 53 54 55 56 57)
        (do
          (bsp/write! bos (+ (.-INT_PACKED_2_ZERO codes) (bit-shift-right lb 8)))
          (bsp/write! bos lb))
        (58 59 60 61 62 63 64)
        (do
          (when (< lb -1)
            (bsp/write! bos (+ (.-INT_PACKED_2_ZERO codes) (bit-shift-right lb 8))))
          (bsp/write! bos lb))
        (throw (js/Error. (str "Long (" l ") can not be converted"))))))
  (write-float! [bos f]
    (do
      (bsp/write! bos (.-FLOAT codes))
      (bsp/write-float! bos f)))
  (write-double! [bos d]
    (case d
      0.0 (bsp/write! bos (.-DOUBLE_0 codes))
      1.0 (bsp/write! bos (.-DOUBLE_1 codes))
      (do
        (bsp/write! bos (.-DOUBLE codes))
        (bsp/write-double! bos d)))))

(defn read-utf8-chars! [dest source offset length]
  (loop [pos offset]
    (when-let [ch (aget source pos)]
      (case (bit-shift-right ch 4)
        (0 1 2 3 4 5 6 7)
        (do
          (.append dest (.fromCharCode js/String ch))
          (recur (inc pos)))
        (12 13)
        (let [ch1 (aget source (inc pos))]
          (do
            (.append
              dest
              (.fromCharCode
                js/String
                (bit-or (bit-and ch1 0x3f) (bit-shift-left (bit-and ch 0x1f) 6))))
            (recur (+ 2 pos))))
        (14)
        (let [ch1 (aget source (inc pos))
              ch2 (aget source (+ 2 pos))]
          (do
            (.append
              dest
              (.fromCharCode
                js/String
                (bit-or
                  (bit-and ch2 0x3f)
                  (bit-shift-left (bit-and ch1 0x3f) 6)
                  (bit-shift-left (bit-and ch 0x0f) 12))))
            (recur (+ 3 pos))))
        (throw (js/Error. (str "Invalid UTF Character (" ch ")")))))))

(defn read-string-buffer! [bis string-buffer byte-len]
  (let [byte-buffer (make-byte-array byte-len)]
    (do
      (bsp/read-bytes! bis byte-buffer 0 byte-len)
      (read-utf8-chars! string-buffer byte-buffer 0 byte-len))))

(extend-type bs/ByteInputStream
  p/FressianReader
  (read-boolean! [bis]
    (let [code (bsp/read! bis)]
      (case code
        ((.-TRUE codes)) true
        ((.-FALSE codes)) false
      (throw (js/Error. (str "Expected boolean got (" code ")"))))))
  (read-float! [bis]
    (let [code (bsp/read! bis)]
      (case code
        ((.-FLOAT codes)) (bsp/read-float! bis)
      (throw (js/Error. (str "Expected float got (" code ")"))))))
  (read-double! [bis]
    (let [code (bsp/read! bis)]
      (case code
        ((.-DOUBLE_0 codes)) 0.0
        ((.-DOUBLE_1 codes)) 1.0
        ((.-DOUBLE codes)) (bsp/read-double! bis))))
  (read-int! [bis]
    (let [code (bsp/read! bis)]
      (case code
        0xFF -1
        (0x00 0x01 0x02 0x03 0x04 0x05 0x06 0x07
         0x08 0x09 0x0A 0x0B 0x0C 0x0D 0x0E 0x0F
         0x10 0x11 0x12 0x13 0x14 0x15 0x16 0x17
         0x18 0x19 0x1A 0x1B 0x1C 0x1D 0x1E 0x1F
         0x20 0x21 0x22 0x23 0x24 0x25 0x26 0x27
         0x28 0x29 0x2A 0x2B 0x2C 0x2D 0x2E 0x2F
         0x30 0x31 0x32 0x33 0x34 0x35 0x36 0x37
         0x38 0x39 0x3A 0x3B 0x3C 0x3D 0x3E 0x3F)
        (bit-and code 0xFF)
        (0x40 0x41 0x42 0x43 0x44 0x45 0x46 0x47
         0x48 0x49 0x4A 0x4B 0x4C 0x4D 0x4E 0x4F
         0x50 0x51 0x52 0x53 0x54 0x55 0x56 0x57
         0x58 0x59 0x5A 0x5B 0x5C 0x5D 0x5E 0x5F)
        (bit-or
          (bit-shift-left (- code (.-INT_PACKED_2_ZERO codes)) 8)
          (bsp/read! bis))
        (0x60 0x61 0x62 0x63 0x64 0x65 0x66 0x67
         0x68 0x69 0x6A 0x6B 0x6C 0x6D 0x6E 0x6F)
        (bit-or
          (bit-shift-left (- code (.-INT_PACKED_3_ZERO codes)) 16)
          (bsp/read-unsigned-int16! bis))
        (0x70 0x71 0x72 0x73)
        (bit-or
          (bit-shift-left (- code (.-INT_PACKED_4_ZERO codes)) 24)
          (bsp/read-unsigned-int24! bis))
        (0x74 0x75 0x76 0x77)
        (let [i32 (bsp/read-unsigned-int32! bis)]
          (case code
            0x74 (+ i32 (* -2 two-power-32))
            0x75 (+ i32 (* -1 two-power-32))
            0x76 i32
            0x77 (+ i32 two-power-32)))
        (0x78 0x79 0x7A 0x7B)
        (let [ih8 (bsp/read! bis)
              il32 (bsp/read-unsigned-int32! bis)
              i40 (+ (* ih8 two-power-32) il32)]
          (case code
            0x78 (+ i40 (* -512 two-power-32))
            0x79 (+ i40 (* -256 two-power-32))
            0x7A i40
            0x7B (+ i40 (* 256 two-power-32))))
        (0x7C 0x7D 0x7E 0x7F)
        (let [ih16 (bsp/read-unsigned-int16! bis)
              il32 (bsp/read-unsigned-int32! bis)
              i48 (+ (* ih16 two-power-32) il32)]
          (case code
            0x7C (+ i48 (* -2 two-power-16 two-power-32))
            0x7D (+ i48 (* -1 two-power-16 two-power-32))
            0x7E i48
            0x7F (+ i48 (* two-power-16 two-power-32))))
        ((.-INT codes))
        (let [ih32 (bsp/read-int32! bis)
              il32 (bsp/read-unsigned-int32! bis)]
          (if (or (> ih32 max-pos-js-hb-int) (< ih32 max-neg-js-hb-int))
            (Long. il32 ih32)
            (+ (* ih32 two-power-32) il32)))
        (throw (js/Error. (str "Expected int64 got (" code ")"))))))
  (read-object! [bis]
    (let [code (bsp/read! bis)]
      (case code
        ((.-NULL codes)) nil
        ((.-TRUE codes)) true
        ((.-FALSE codes)) false
        0xFF -1
        (0x00 0x01 0x02 0x03 0x04 0x05 0x06 0x07
         0x08 0x09 0x0A 0x0B 0x0C 0x0D 0x0E 0x0F
         0x10 0x11 0x12 0x13 0x14 0x15 0x16 0x17
         0x18 0x19 0x1A 0x1B 0x1C 0x1D 0x1E 0x1F
         0x20 0x21 0x22 0x23 0x24 0x25 0x26 0x27
         0x28 0x29 0x2A 0x2B 0x2C 0x2D 0x2E 0x2F
         0x30 0x31 0x32 0x33 0x34 0x35 0x36 0x37
         0x38 0x39 0x3A 0x3B 0x3C 0x3D 0x3E 0x3F)
        (bit-and code 0xFF)
        (0x40 0x41 0x42 0x43 0x44 0x45 0x46 0x47
         0x48 0x49 0x4A 0x4B 0x4C 0x4D 0x4E 0x4F
         0x50 0x51 0x52 0x53 0x54 0x55 0x56 0x57
         0x58 0x59 0x5A 0x5B 0x5C 0x5D 0x5E 0x5F)
        (bit-or
          (bit-shift-left (- code (.-INT_PACKED_2_ZERO codes)) 8)
          (bsp/read! bis))
        (0x60 0x61 0x62 0x63 0x64 0x65 0x66 0x67
         0x68 0x69 0x6A 0x6B 0x6C 0x6D 0x6E 0x6F)
        (bit-or
          (bit-shift-left (- code (.-INT_PACKED_3_ZERO codes)) 16)
          (bsp/read-unsigned-int16! bis))
        (0x70 0x71 0x72 0x73)
        (bit-or
          (bit-shift-left (- code (.-INT_PACKED_4_ZERO codes)) 24)
          (bsp/read-unsigned-int24! bis))
        (0x74 0x75 0x76 0x77)
        (let [i32 (bsp/read-unsigned-int32! bis)]
          (case code
            0x74 (+ i32 (* -2 two-power-32))
            0x75 (+ i32 (* -1 two-power-32))
            0x76 i32
            0x77 (+ i32 two-power-32)))
        (0x78 0x79 0x7A 0x7B)
        (let [ih8 (bsp/read! bis)
              il32 (bsp/read-unsigned-int32! bis)
              i40 (+ (* ih8 two-power-32) il32)]
          (case code
            0x78 (+ i40 (* -512 two-power-32))
            0x79 (+ i40 (* -256 two-power-32))
            0x7A i40
            0x7B (+ i40 (* 256 two-power-32))))
        (0x7C 0x7D 0x7E 0x7F)
        (let [ih16 (bsp/read-unsigned-int16! bis)
              il32 (bsp/read-unsigned-int32! bis)
              i48 (+ (* ih16 two-power-32) il32)]
          (case code
            0x7C (+ i48 (* -2 two-power-16 two-power-32))
            0x7D (+ i48 (* -1 two-power-16 two-power-32))
            0x7E i48
            0x7F (+ i48 (* two-power-16 two-power-32))))
        ((.-INT codes))
        (let [ih32 (bsp/read-int32! bis)
              il32 (bsp/read-unsigned-int32! bis)]
          (if (or (> ih32 max-pos-js-hb-int) (< ih32 max-neg-js-hb-int))
            (Long. il32 ih32)
            (+ (* ih32 two-power-32) il32)))
        ((.-STRING codes))
        (let [string-buffer (StringBuffer.)]
          (do
            (read-string-buffer! bis string-buffer (p/read-object! bis))
            (.toString string-buffer)))
        (0xDA 0xDB 0xDC 0xDD 0xDE 0xDF 0xE0 0xE1)
        (let [string-buffer (StringBuffer.)]
          (do
            (read-string-buffer! bis string-buffer (- code (.-STRING_PACKED_LENGTH_START codes)))
            (.toString string-buffer)))
        ((.-STRING_CHUNK codes))
        (let [string-buffer (StringBuffer.)]
          (do
            (read-string-buffer! bis string-buffer (p/read-object! bis))
            (loop []
              (let [next-code (bsp/read! bis)]
                (case next-code
                  ((.-STRING codes))
                  (read-string-buffer! bis string-buffer (p/read-object! bis))
                  (0xDA 0xDB 0xDC 0xDD 0xDE 0xDF 0xE0 0xE1)
                  (read-string-buffer! bis string-buffer (- next-code (.-STRING_PACKED_LENGTH_START codes)))
                  ((.-STRING_CHUNK codes))
                    (do
                      (read-string-buffer! bis string-buffer (p/read-object! bis))
                      (recur))
                  (throw (js/Error. (str "Expected chunked string (" next-code ")"))))))
            (.toString string-buffer)))
        ((.-BYTES codes))
        (let [length (p/read-object! bis)
              ba (make-byte-array length)]
          (do
            (bsp/read-bytes! bis ba 0 length)
            ba))
        (0xD0 0xD1 0xD2 0xD3 0xD4 0xD5 0xD6 0xD7)
        (let [length (- code (.-BYTES_PACKED_LENGTH_START codes))
              ba (make-byte-array length)]
          (do
            (bsp/read-bytes! bis ba 0 length)
            ba))
        ((.-BYTES_CHUNK codes))
        (let [chunks (array)]
          (do
            (loop [code code]
              (cond
                (== code (.-BYTES_CHUNK codes))
                (let [length (p/read-object! bis)
                      ba (make-byte-array length)]
                  (do
                    (bsp/read-bytes! bis ba 0 length)
                    (.push chunks ba)
                    (recur (bsp/read! bis))))
                (== code (.-BYTES codes))
                (let [length (p/read-object! bis)
                      ba (make-byte-array length)]
                  (do
                    (bsp/read-bytes! bis ba 0 length)
                    (.push chunks ba)))
                :else
                (throw (js/Error. (str "conclusion of chunked bytes (" code ")")))))
            (let [length (reduce #(+ %1 (alength %2)) 0 (seq chunks))
                  ba (make-byte-array length)]
               (do
                 (loop [pos 0 chunk 0]
                   (when (< chunk (alength chunks))
                     (.set ba (aget chunks chunk) pos)
                     (recur (+ pos (alength (aget chunks chunk))) (inc chunk))))
                 ba))))
        ((.-FLOAT codes)) (bsp/read-float! bis)
        ((.-DOUBLE_0 codes)) 0.0
        ((.-DOUBLE_1 codes)) 1.0
        ((.-DOUBLE codes)) (bsp/read-double! bis)))))
