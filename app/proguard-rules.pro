# No rules needed at present. Virgil talks to every vendor over plain HTTP in
# the OpenAI-compatible wire format, so nothing here depends on reflection.
#
# If com.anthropic:anthropic-java is ever reintroduced, the five rules it needs
# under R8 are recorded verbatim on issue #4, along with the failure each one
# was earned against. Do not re-derive them.
