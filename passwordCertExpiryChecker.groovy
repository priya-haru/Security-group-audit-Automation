def call(Map buildParams) {
    pipeline {
        agent any

        options {
            timestamps()
            timeout(time: 20, unit: 'MINUTES')
            buildDiscarder(logRotator(numToKeepStr: '10'))
            disableConcurrentBuilds()
        }

        parameters {
            string(name: 'PROD_WEBHOOK_ID', trim: true, defaultValue: 'googlechat_webtoken_airina_alerts_testing', description: 'Webhook ID or URL for PROD')
            string(name: 'NONPROD_WEBHOOK_ID', trim: true, defaultValue: 'googlechat_webtoken_airina_alerts_testing', description: 'Webhook ID or URL for NONPROD')
            string(name: 'BRANCH', defaultValue: 'master', description: 'Library branch name')
            booleanParam(name: 'SEND_CHAT_MESSAGE', defaultValue: true, description: 'Set to false to disable webhook messages')
            string(name: 'DAYS_BEFORE_NOTIFICATION', defaultValue: '14', description: 'Days before expiry to trigger notification')
            booleanParam(name: 'RECURSIVE', defaultValue: true, description: 'Notify all items expiring in next N days if true, else exact matches only')
            choice(name: 'CONFLUENCE_PAGE_TYPE', choices: ['Tokens', 'Certificates'], description: 'Page type')
        }

        environment {
            PYTHON_BASE_IMAGE_TAG = 'docker-****-virtual.artifactory.2b82.aws.cloud.****.corp/jenkins/slaves/python:3.11'
            PYTHON_FINAL_CONTAINER = 'artifactory.fr.eu.****.corp:40009/devops/python311:1.0.1'
            PYTHON_FILE_NAME = 'passwordCertExpiryChecker'

            CONFLUENCE_PAGE_TOKENS = '123456789'
            CONFLUENCE_PAGE_CERTIFICATES = '364196392'

            EXPIRY_DATE_COLUMN_NAME_TOKENS = 'Next expiration date'
            EXPIRY_DATE_COLUMN_NAME_CERTIFICATES = 'Expiry Date'

            ENVIRONMENT_COLUMN_NAME_TOKENS = 'Channel'
            ENVIRONMENT_COLUMN_NAME_CERTIFICATES = 'Environment'

            TEXT_COLUMN_NAME_TOKENS = 'Description'
            TEXT_COLUMN_NAME_CERTIFICATES = 'Common Name'
        }

        stages {
            stage('Validation') {
                steps {
                    script {
                        def today = new Date().format('yyyy-MM-dd')
                        currentBuild.description = "Check ${params.CONFLUENCE_PAGE_TYPE} - ${today}"

                        // Set parameters based on chosen Confluence page type
                        def CONFLUENCE_PAGE_ID
                        def ENVIRONMENT_COLUMN_NAME
                        def TEXT_COLUMN_NAME
                        def EXPIRY_DATE_COLUMN_NAME

                        if (params.CONFLUENCE_PAGE_TYPE == 'Tokens') {
                            CONFLUENCE_PAGE_ID = env.CONFLUENCE_PAGE_TOKENS
                            ENVIRONMENT_COLUMN_NAME = env.ENVIRONMENT_COLUMN_NAME_TOKENS
                            TEXT_COLUMN_NAME = env.TEXT_COLUMN_NAME_TOKENS
                            EXPIRY_DATE_COLUMN_NAME = env.EXPIRY_DATE_COLUMN_NAME_TOKENS
                        } else if (params.CONFLUENCE_PAGE_TYPE == 'Certificates') {
                            CONFLUENCE_PAGE_ID = env.CONFLUENCE_PAGE_CERTIFICATES
                            ENVIRONMENT_COLUMN_NAME = env.ENVIRONMENT_COLUMN_NAME_CERTIFICATES
                            TEXT_COLUMN_NAME = env.TEXT_COLUMN_NAME_CERTIFICATES
                            EXPIRY_DATE_COLUMN_NAME = env.EXPIRY_DATE_COLUMN_NAME_CERTIFICATES
                        } else {
                            error("Invalid CONFLUENCE_PAGE_TYPE. Choose 'Tokens' or 'Certificates'")
                        }

                        // Resolve PROD webhook URL
                        def PROD_WEBHOOK = ''
                        if (params.PROD_WEBHOOK_ID.startsWith('https')) {
                            PROD_WEBHOOK = params.PROD_WEBHOOK_ID
                        } else {
                            withCredentials([string(credentialsId: params.PROD_WEBHOOK_ID, variable: 'webhook_url')]) {
                                PROD_WEBHOOK = webhook_url
                            }
                        }

                        // Resolve NONPROD webhook URL
                        def NONPROD_WEBHOOK = ''
                        if (params.NONPROD_WEBHOOK_ID.startsWith('https')) {
                            NONPROD_WEBHOOK = params.NONPROD_WEBHOOK_ID
                        } else {
                            withCredentials([string(credentialsId: params.NONPROD_WEBHOOK_ID, variable: 'webhook_url')]) {
                                NONPROD_WEBHOOK = webhook_url
                            }
                        }

                        // Download python libs and script from private repo with token
                        withCredentials([string(credentialsId: 'REST_USER_GITHUB_TOKEN', variable: 'github_token')]) {
                            sh "curl -O https://${github_token}@gheprivate.intra.corp/raw/AIRINA/jenkins-pipeline-library/master/py/confluence_lib.py"
                            sh "curl -O https://${github_token}@gheprivate.intra.corp/raw/AIRINA/jenkins-pipeline-library/${params.BRANCH}/py/${env.PYTHON_FILE_NAME}.py"
                            sh "curl -O https://${github_token}@gheprivate.intra.corp/raw/AIRINA/jenkins-pipeline-library/master/dockerfiles/py/Dockerfile"
                        }

                        // Archive artifacts for debugging or inspection
                        archiveArtifacts artifacts: "${env.PYTHON_FILE_NAME}.py"
                        archiveArtifacts artifacts: "Dockerfile"

                        // Save to env for use in later stages
                        env.CONFLUENCE_PAGE_ID = CONFLUENCE_PAGE_ID
                        env.ENVIRONMENT_COLUMN_NAME = ENVIRONMENT_COLUMN_NAME
                        env.TEXT_COLUMN_NAME = TEXT_COLUMN_NAME
                        env.EXPIRY_DATE_COLUMN_NAME = EXPIRY_DATE_COLUMN_NAME
                        env.PROD_WEBHOOK = PROD_WEBHOOK
                        env.NONPROD_WEBHOOK = NONPROD_WEBHOOK
                    }
                }
            }

            stage('Build Python Image') {
                steps {
                    script {
                        // Get user/group id or fallback to 1000 if not available
                        env.JENKINS_GROUP_ID = sh(script: 'id -g', returnStdout: true).trim() ?: '1000'
                        env.JENKINS_USER_ID = sh(script: 'id -u', returnStdout: true).trim() ?: '1000'

                        dockerHelper.cleanupDockerImages()
                        try {
                            sh "docker pull ${env.PYTHON_BASE_IMAGE_TAG}"

                            sh """
                                docker build \\
                                --build-arg JENKINS2_GID=${env.JENKINS_GROUP_ID} \\
                                --build-arg JENKINS2_UID=${env.JENKINS_USER_ID} \\
                                -f Dockerfile -t ${env.PYTHON_FINAL_CONTAINER} .
                            """
                        } catch (err) {
                            error("Docker Build failed: ${err}")
                        }
                        dockerHelper.cleanupDockerImages()
                    }
                }
            }

            stage('Fetch & Notify') {
                steps {
                    script {
                        catchError(buildResult: currentBuild.result, stageResult: 'FAILURE') {
                            withCredentials([string(credentialsId: 'REST_USER_CONFLUENCE_TOKEN', variable: 'confluence_token')]) {
                                def scriptArgs = [
    "--target-page \"${env.CONFLUENCE_PAGE_ID}\"",
    "--text-column-name \"${env.TEXT_COLUMN_NAME}\"",
    "--env-column-name \"${env.ENVIRONMENT_COLUMN_NAME}\"",
    "--prod-webhook-url \"${env.PROD_WEBHOOK}\"",
    "--non-prod-webhook-url \"${env.NONPROD_WEBHOOK}\"",
    "--send-chat-message=${params.SEND_CHAT_MESSAGE}",
    "--expiry-date-column-name \"${env.EXPIRY_DATE_COLUMN_NAME}\"",
    "--expiry-date-limit \"${params.DAYS_BEFORE_NOTIFICATION}\""
]

if (params.RECURSIVE != null) {
    scriptArgs << "--recursive ${params.RECURSIVE}"
}


                                docker.image(env.PYTHON_FINAL_CONTAINER).inside {
                                    withEnv(["CONF_TOKEN=${confluence_token}"]) {
                                        sh "python3.11 ${env.PYTHON_FILE_NAME}.py -c \$CONF_TOKEN ${scriptArgs.join(' ')}"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        post {
            always {
                script {
                    def failureUrl = 'id:googlechat_webtoken_airinared_nonprod'
                    healthcheckNotification.notifyOnFailure(failureUrl, "*Certificate/token expiry checker failed*\n${env.BUILD_URL}")
                    cleanWs()
                }
            }
        }
    }
}
