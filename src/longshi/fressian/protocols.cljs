(ns longshi.fressian.protocols)

(defprotocol SeekStream
  (seek! [ss pos]))

(defprotocol WriteStream
  (write! [ws b])
  (write-bytes! [ws b off len])
  (get-bytes [ws]))

(defprotocol ReadStream
  (read! [rs])
  (read-bytes! [rs b off len])
  (available [rs]))

(defprotocol FressianWriter
  (write-null! [fw])
  (write-boolean! [fw b])
  (write-int! [fw i])
  (write-double! [fw d]))

(defprotocol FressianReader
  (read-double! [fr])
  (read-object! [fr]))
