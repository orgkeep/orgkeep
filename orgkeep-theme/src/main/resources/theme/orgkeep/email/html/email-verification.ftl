<#import "template.ftl" as layout>
<@layout.emailLayout>
<@layout.title text=msg("orgkeepEmailVerificationTitle")/>
<p style="margin: 0;">${msg("orgkeepEmailVerificationText", realmName)}</p>
<@layout.button link=link label=msg("orgkeepEmailVerificationButton")/>
<@layout.note>${msg("orgkeepLinkExpiration", linkExpirationFormatter(linkExpiration))}</@layout.note>
<@layout.note>${msg("orgkeepEmailVerificationIgnore")}</@layout.note>
</@layout.emailLayout>
