<#import "template.ftl" as layout>
<@layout.emailLayout>
<@layout.title text=msg("orgkeepPasswordResetTitle")/>
<p style="margin: 0;">${msg("orgkeepPasswordResetText", realmName)}</p>
<@layout.button link=link label=msg("orgkeepPasswordResetButton")/>
<@layout.note>${msg("orgkeepLinkExpiration", linkExpirationFormatter(linkExpiration))}</@layout.note>
<@layout.note>${msg("orgkeepPasswordResetIgnore")}</@layout.note>
</@layout.emailLayout>
