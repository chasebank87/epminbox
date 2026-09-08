/*
 Template: Groovy rule
 Name: 000-groovy-rule-template.groovy
 Owner:
 Created: 2026-09-08
 Updated: 2026-09-08
 Purpose:
   - Starter skeleton for a Calculation Manager Groovy rule
 Inputs:
   - (define RTPs / connections)
 Outputs:
   - (define pass/fail or side effects)
*/

def runRule() {
    // TODO: implement rule logic
    return [status: "ok", message: "stub"]
}

def result = runRule()
println("Rule result: ${result}")
