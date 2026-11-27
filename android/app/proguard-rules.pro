# Styli release rules.
#
# AndroidX, Compose, protobuf-javalite and androidx.ink ship their own consumer rules.
# The generated messages are read by field name through protobuf reflection, so keeping their field names is required;
# without this, R8 renames width_ etc. and opening the library crashes.
-keep class app.styli.ink.proto.** { *; }

# If the Go core is embedded with gomobile later, add targeted rules here for the generated go.** bindings, go.Seq
# and any JNI callbacks. Do not add a blanket keep for app.styli.ink.** because that defeats R8.
