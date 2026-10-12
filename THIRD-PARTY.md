# Mozilla CA root bundle

`app/src/main/res/raw/ca_bundle.pem` is the unmodified PEM bundle downloaded from
https://curl.se/ca/cacert.pem on 2026-10-12. The header dates Mozilla's data to
2026-09-25. It is distributed under Mozilla Public License 2.0; the full license
is in MOZILLA-LICENSE.txt. Source data and conversion script locations appear in
the PEM header and at https://curl.se/docs/caextract.html.

The PEM representation does not carry Firefox's external root constraints.
This app uses normal X.509 chain validation and HTTPS hostname checking.

The org.json jar used for desktop tests is not packaged in the app or source ZIP.
Android supplies its own org.json implementation.
