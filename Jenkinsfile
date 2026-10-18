// Alternative to GitHub Actions / GitLab CI. Needs a JDK 17 tool named 'jdk17' on the agent.
pipeline {
    agent any
    tools { jdk 'jdk17' }
    stages {
        stage('Checkout') { steps { checkout scm } }
        stage('Build')    { steps { sh './mvnw -B -DskipTests clean package' } }
        stage('Test')     { steps { sh './mvnw -B verify' } }
        // Fails the build on CVSS >= 7 (High/Critical)
        stage('Dependency-Check') { steps { sh './mvnw -B -Pci -DskipTests verify' } }
        stage('Publish') {
            steps {
                junit 'target/surefire-reports/*.xml'
                archiveArtifacts artifacts: 'target/dependency-check-report.html', allowEmptyArchive: true
            }
        }
    }
}
